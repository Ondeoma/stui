package stui.api

import cats.effect.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import sbirds.http.{Errors, RouteHelpers}
import sbirds.shared.AppError

import java.util.UUID

/**
 * *Routes に共通の補助（エラー応答・認証・権限の確認）。本文・クエリ・パスの UUID・302 は sbirds.http.RouteHelpers。
 */
trait RouteSupport extends RouteHelpers {
  protected def sessions: SessionAuth
  protected def access: Access2

  protected def bodyInvalid: sbirds.shared.ErrorCode = ErrorCode.RequestBodyInvalid

  // 本文の読み取りの途中のエラー（大きすぎる本文を含む）は、これまでどおり RequestBodyInvalid（400）にする
  override protected def bodyKeepsAppError: Boolean = false

  /** エラーの応答（コード付きでログに出る）. detail を省くとコードの既定の文言. */
  protected def err(code: ErrorCode, detail: String | Null = null): IO[Response[IO]] =
    Errors.respond(AppError(code, Option(detail)))

  protected def fail(e: AppError): IO[Response[IO]] = Errors.respond(e)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/messages/lookup", "/api/messages/batch", "/api/channels/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST &&
    readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.NotLoggedIn)
      case Some(s) =>
        // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
        sbirds.observe.Telemetry.withUser(s.user.id.toString, s.user.name) {
          if !s.canWrite && !Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method) && !readOnlyPost(req)
          then
            err(ErrorCode.ApiKeyNeedsWrite)
          else f(s)
        }
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(ErrorCode.SessionOnly))

  /** システム管理者の、Cookie のセッションのみ（エージェント専用アカウントの管理など。APIキーでは admin スコープがあっても不可）。 */
  protected def adminSession(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    admin(req)(s => if s.isSession then f(s) else err(ErrorCode.SessionOnly))

  /** チャンネルを見られることを確認してから処理する。 */
  protected def withChannel(req: Request[IO],
                            id: UUID,
                            need: Need)(f: (AuthSession, ChannelCtx) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => respond(access.channel(s, id, need))(c => f(s, c)))

  /** メッセージ（とそのチャンネル）を見られることを確認してから処理する。 */
  protected def withMessage(
    req: Request[IO],
    id: UUID,
    need: Need)(f: (AuthSession, ChannelCtx, MessageRow) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s =>
      respond(access.message(s, id, need))((c, m) => f(s, c, m))
    )

  /** multipart/form-data の file を一時ファイルに受け取る（上限を超えたら 413）。 */
  protected def withUpload(req: Request[IO], maxBytes: Long)(f: Upload => IO[Response[IO]]): IO[Response[IO]] =
    // 大きい部分は一時ファイルに受ける（既定のデコーダーは全体をメモリに載せるので、同時のアップロードでメモリを使い切る）。
    // 一時ファイルは use を抜けると消えるので、受け取った後の処理もこの中で行う
    EntityDecoder.mixedMultipartResource[IO](maxSizeBeforeWrite = 1024 * 1024).use(dec =>
      dec.decode(req, strict = true).value.attempt.flatMap {
        case Right(Right(mp)) =>
          mp.parts.find(_.name.contains("file")) match {
            case None => err(ErrorCode.UploadFileMissing)
            case Some(part) =>
              val name =
                part.filename.map(_.trim).filter(_.nonEmpty).getOrElse("file").replaceAll(
                  "[\\\\/\\r\\n]",
                  "_"
                ).take(200)
              val ct = part.contentType.map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}").getOrElse(
                "application/octet-stream"
              )
              fs2.io.file.Files[IO].tempFile.use { tmp =>
                part.body.take(maxBytes + 1).through(fs2.io.file.Files[IO].writeAll(tmp)).compile.drain *>
                  fs2.io.file.Files[IO].size(tmp).flatMap { size =>
                    if size > maxBytes then
                      err(ErrorCode.UploadTooLarge, s"ファイルが大きすぎます（上限 ${math.max(1, maxBytes / 1024 / 1024)}MB）")
                    else f(Upload(name, ct, tmp, size))
                  }
              }
          }
        case _ => err(ErrorCode.UploadNotMultipart)
      }
    )

}
