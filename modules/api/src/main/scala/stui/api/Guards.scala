package stui.api

import cats.data.OptionT
import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import org.http4s.server.middleware.EntityLimiter
import sbirds.http.Errors
import stui.core.ErrorCode

/**
 * すべての要求に共通の守り:
 *  - 本文の大きさの上限（アップロードは MAX_UPLOAD_MB、それ以外は 2MB）。大きすぎる本文でメモリを使い切らせない。
 *  - セキュリティのヘッダー（iframe の禁止・nosniff・Referrer・https なら HSTS）。
 *  - Cookie のセッションで状態を変える要求は、別のサイト（Origin が違う）からなら断る（同じ登録ドメインの別のサービスからの CSRF も防ぐ）。
 */
object Guards {

  private val JsonLimit: Long = 2L * 1024 * 1024
  private val safe = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  private def isUpload(req: Request[IO]): Boolean = {
    val p = req.pathInfo.renderString
    req.method == Method.POST &&
    (p.matches("^/api/channels/[^/]+/files$") || p == "/api/me/avatar" || p == "/api/emoji")
  }

  private def originOf(u: String): String = Uri.fromString(u).toOption.map(x =>
    s"${x.scheme.fold("")(_.value)}://${x.authority.fold("")(_.renderString)}"
  ).getOrElse(u)

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    val allowed = originOf(cfg.publicUrl).toLowerCase
    def foreign(req: Request[IO]): Boolean =
      !safe.contains(req.method) &&
        req.headers.get[Authorization].isEmpty &&
        req.cookies.exists(_.name == cfg.cookieName) &&
        req.headers.get(CIString("Origin")).map(_.head.value.trim.toLowerCase).exists { o =>
          // "null"（サンドボックスの iframe など）も別のサイトとして扱う
          o != allowed && !(cfg.devLogin && (o.startsWith("http://localhost:") || o.startsWith("http://127.0.0.1:")))
        }
    HttpRoutes[IO] { req =>
      val limit = if isUpload(req) then cfg.maxUploadBytes + 1024 * 1024 else JsonLimit
      if foreign(req) then
        OptionT.liftF(Errors.respond(ErrorCode.CrossSiteRequest))
      else if req.contentLength.exists(_ > limit) then
        OptionT.liftF(Errors.respond(ErrorCode.RequestTooLarge))
      else
        // 本文を読む途中で上限を超えたとき（Content-Length がない要求など）も 413 にする
        OptionT(EntityLimiter.httpRoutes(app, limit).run(req).value.recoverWith {
          case _: EntityLimiter.EntityTooLarge => Errors.respond(ErrorCode.RequestTooLarge).map(Some(_))
        })
    }.map(secure(_, cfg.publicUrl.startsWith("https://")))
  }

  /** すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。 */
  private def secure(r: Response[IO], https: Boolean): Response[IO] = {
    val base = List(
      "X-Frame-Options" -> "DENY",
      "Content-Security-Policy" -> "frame-ancestors 'none'; base-uri 'self'; object-src 'none'",
      "X-Content-Type-Options" -> "nosniff",
      "Referrer-Policy" -> "same-origin"
    ) ++ (if https then List("Strict-Transport-Security" -> "max-age=31536000") else Nil)
    base.foldLeft(r) { case (acc, (k, v)) =>
      if acc.headers.get(CIString(k)).isDefined then acc else acc.putHeaders(k -> v)
    }
  }
}
