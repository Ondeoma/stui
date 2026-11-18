package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import stui.auth.{ApiKeys, Crypto}
import stui.core.*
import stui.db.*
import stui.shared.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

final case class ApiConfig(
    adminGroups: Set[String],
    cookieSecure: Boolean,
    publicUrl: String,
    sessionTtl: FiniteDuration = 14.days,
    cookieName: String = "stui_session",
    devLogin: Boolean = false,
    maxUploadBytes: Long = 100L * 1024 * 1024,
    timezone: String = "Asia/Tokyo",
    webhookAllowHttp: Boolean = false,
    webhookAllowPrivate: Boolean = false,
    version: String = "0.1.0",
    oauthClients: List[OAuthClient] = Nil,
    linkServices: List[LinkService] = Nil
):
  def service(provider: String): Option[LinkService] = linkServices.find(_.provider == provider)

/**
 * 連携する兄弟のサービス（skiwi・skawau・skea・skorimako はリンクの名前、skaka は会議室の作成）。相手に stui をクライアントとして登録しておく。
 * url は画面から開く URL、internalUrl はサーバーから呼ぶ URL（省略時は url）。
 */
final case class LinkService(provider: String, url: String, clientId: String, clientSecret: String, internalUrl: Option[String] = None):
  def apiUrl: String = internalUrl.getOrElse(url)

/** 認可を求める外部のサービス（OAUTH_CLIENTS で登録する）。redirectUri は完全一致で照合する。scopes は許すスコープ（read・write）。 */
final case class OAuthClient(id: String, secret: String, redirectUri: String, name: String, scopes: List[String] = List("read"))

/** 処理の失敗（HTTP の応答に変換する）。 */
enum Fail:
  case NotFound(msg: String)
  case Forbidden(msg: String)
  case Invalid(msg: String)
  case Conflict(msg: String)
  case Unauthorized(msg: String)

  def message: String = this match
    case NotFound(m)     => m
    case Forbidden(m)    => m
    case Invalid(m)      => m
    case Conflict(m)     => m
    case Unauthorized(m) => m

object Fail:
  def from(e: StoreError): Fail = e match
    case StoreError.NotFound(w) => Fail.NotFound(s"$w が見つかりません")
    case StoreError.Invalid(m)  => Fail.Invalid(m)
    case StoreError.Conflict(m) => Fail.Conflict(m)

type Res[A] = IO[Either[Fail, A]]

extension [A](io: IO[Either[StoreError, A]]) def orFail: Res[A] = io.map(_.left.map(Fail.from))

/** 操作する人と、自動化の連鎖の深さ（ルールが別のルールを起動する深さ）。 */
final case class Op(s: AuthSession, depth: Int = 0):
  def userId: UUID = s.user.id
  def via: String  = s.viaString
  def today: LocalDate = LocalDate.now(Op.zone(s.user.timezone))

object Op:
  def zone(tz: String): ZoneId = scala.util.Try(ZoneId.of(tz)).getOrElse(ZoneId.of("Asia/Tokyo"))

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig):

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    bearer(req) match
      case Some(token) =>
        users.findKey(Crypto.sha256(token), Instant.now()).map(_.map { (u, gs, kid, scopes, client) =>
          val mcp = req.pathInfo.renderString.startsWith("/mcp") || req.headers.get(org.typelevel.ci.CIString("X-Stui-Mcp")).isDefined
          AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, client, mcp))
        })
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match
          case None        => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  /** 別のユーザーとしての権限確認用（通知の宛先・自動化の実行ユーザー）。無効なユーザーは None。 */
  def sessionFor(userId: UUID, via: AuthVia = AuthVia.Session): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs), via)))

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(ApiKeys.looksLike)

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] =
    val token = Crypto.randomToken()
    users.createSession(Crypto.sha256(token), user.id, Instant.now().plusSeconds(cfg.sessionTtl.toSeconds), idToken).as(cookie(token, cfg.sessionTtl.toSeconds))

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None    => IO.pure(None -> cookie("", 0))

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(cfg.cookieName, value, maxAge = Some(maxAge), path = Some("/"), httpOnly = true, secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax))

/** *Routes に共通の補助（エラー応答・認証・リクエスト本文・権限の確認）。 */
/** 受け取ったファイル（一時ファイル）。 */
final case class Upload(name: String, contentType: String, path: fs2.io.file.Path, size: Long)

trait RouteSupport extends Http4sDsl[IO]:
  protected def sessions: SessionAuth
  protected def access: Access2

  protected def err(status: Status, msg: String): IO[Response[IO]] =
    Response[IO](status).withEntity(ErrorDto(msg)).pure[IO]

  protected def fail(f: Fail): IO[Response[IO]] = f match
    case Fail.NotFound(m)     => err(Status.NotFound, m)
    case Fail.Forbidden(m)    => err(Status.Forbidden, m)
    case Fail.Invalid(m)      => err(Status.BadRequest, m)
    case Fail.Conflict(m)     => err(Status.Conflict, m)
    case Fail.Unauthorized(m) => err(Status.Unauthorized, m)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/messages/lookup", "/api/messages/batch", "/api/channels/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST && readOnlyPosts.contains(req.pathInfo.renderString)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(Status.Unauthorized, "ログインしてください")
      case Some(s) =>
        // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
        stui.observe.Telemetry.withUser(s.user.id.toString, s.user.name) {
          if !s.canWrite && !Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method) && !readOnlyPost(req) then
            err(Status.Forbidden, "このAPIキーは参照専用です（write スコープが必要です）")
          else f(s)
        }
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(Status.Forbidden, "管理者だけが使えます"))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(Status.Forbidden, "この操作は画面からのみ行えます"))

  /** システム管理者の、Cookie のセッションのみ（エージェント専用アカウントの管理など。APIキーでは admin スコープがあっても不可）。 */
  protected def adminSession(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    admin(req)(s => if s.isSession then f(s) else err(Status.Forbidden, "この操作は画面からのみ行えます"))

  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e)  => err(Status.BadRequest, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  /** チャンネルを見られることを確認してから処理する。 */
  protected def withChannel(req: Request[IO], id: UUID, need: Need)(f: (AuthSession, ChannelCtx) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => respond(access.channel(s, id, need))(c => f(s, c)))

  /** メッセージ（とそのチャンネル）を見られることを確認してから処理する。 */
  protected def withMessage(req: Request[IO], id: UUID, need: Need)(f: (AuthSession, ChannelCtx, MessageRow) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => respond(access.message(s, id, need))((c, m) => f(s, c, m)))

  /** multipart/form-data の file を一時ファイルに受け取る（上限を超えたら 413）。 */
  protected def withUpload(req: Request[IO], maxBytes: Long)(f: Upload => IO[Response[IO]]): IO[Response[IO]] =
    req.as[org.http4s.multipart.Multipart[IO]].attempt.flatMap {
      case Left(_) => err(Status.BadRequest, "multipart/form-data で file を送ってください")
      case Right(mp) =>
        mp.parts.find(_.name.contains("file")) match
          case None => err(Status.BadRequest, "file がありません")
          case Some(part) =>
            val name = part.filename.map(_.trim).filter(_.nonEmpty).getOrElse("file").replaceAll("[\\\\/\\r\\n]", "_").take(200)
            val ct   = part.contentType.map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}").getOrElse("application/octet-stream")
            fs2.io.file.Files[IO].tempFile.use { tmp =>
              part.body.take(maxBytes + 1).through(fs2.io.file.Files[IO].writeAll(tmp)).compile.drain *>
                fs2.io.file.Files[IO].size(tmp).flatMap { size =>
                  if size > maxBytes then err(Status.PayloadTooLarge, s"ファイルが大きすぎます（上限 ${math.max(1, maxBytes / 1024 / 1024)}MB）")
                  else f(Upload(name, ct, tmp, size))
                }
            }
    }

  protected object UuidVar:
    def unapply(s: String): Option[UUID] = uuidOpt(s)

  /** 同じサイトの中のパスか（ログイン・連携の後に戻る先。"//…"・"/\…" は別のサイトになるので除く）。 */
  protected def localPath(p: String): Boolean =
    p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(_.isControl)

  protected def param(req: Request[IO], k: String): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  protected def intParam(req: Request[IO], k: String, default: Int, max: Int): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)

/** チャンネルで必要な権限。Read は閲覧（公開チャンネルは誰でも）、Post は投稿（メンバー・アーカイブ済みは不可）、Manage は管理。 */
enum Need:
  case Read, Member, Post, Manage

/** 権限を確かめたチャンネルと、自分のメンバーの情報。 */
final case class ChannelCtx(channel: ChannelRow, member: Option[MemberRow]):
  def id: UUID = channel.id
  def isMember: Boolean = member.isDefined
  def isChannelAdmin: Boolean = member.exists(_.role == ChannelRole.Admin)

/** 権限の確認（ルートとサービスで共通）。見えないものは 404、権限が足りなければ 403。 */
final class Access2(channels: ChannelStore, messages: MessageStore):

  def channel(s: AuthSession, id: UUID, need: Need): Res[ChannelCtx] =
    channels.access(id, s.user.id).map {
      case None => Left(Fail.NotFound("チャンネルが見つかりません"))
      case Some((c, m)) =>
        val ctx = ChannelCtx(c, m)
        // 非公開チャンネル・DM はメンバーにしか存在を見せない
        if !c.isPublic && m.isEmpty then Left(Fail.NotFound("チャンネルが見つかりません"))
        else need match
          case Need.Read => Right(ctx)
          case Need.Member => if m.isDefined then Right(ctx) else Left(Fail.Forbidden("チャンネルに参加してください"))
          case Need.Post =>
            if c.archived then Left(Fail.Forbidden("アーカイブしたチャンネルには投稿できません"))
            else if m.isEmpty then Left(Fail.Forbidden("チャンネルに参加してから投稿してください"))
            else if !s.canWrite then Left(Fail.Forbidden("このAPIキーは参照専用です"))
            else Right(ctx)
          case Need.Manage =>
            if c.isDm then Left(Fail.Forbidden("DM の設定は変えられません"))
            else if ctx.isChannelAdmin || s.canAdmin then Right(ctx)
            else Left(Fail.Forbidden("チャンネルの管理者だけが変えられます"))
    }

  def message(s: AuthSession, id: UUID, need: Need): Res[(ChannelCtx, MessageRow)] =
    messages.find(id).flatMap {
      case None => IO.pure(Left(Fail.NotFound("メッセージが見つかりません")))
      case Some(m) => channel(s, m.channelId, need).map {
        case Left(Fail.NotFound(_)) => Left(Fail.NotFound("メッセージが見つかりません"))
        case other                  => other.map(_ -> m)
      }
    }

/**
 * APIキーの発行（自分用のキーと、管理者が発行するエージェント専用アカウントのキーで共通）。
 * adminScope は admin スコープを付けられないときの失敗（付けてよければ None）。平文のキーは応答で1回だけ返す。
 */
object ApiKeyIssue:
  def apply(users: UserStore, userId: UUID, r: CreateApiKeyReq, adminScope: Option[Fail]): Res[CreatedApiKeyDto] =
    val scopes = r.scopes.map(_.trim.toLowerCase).distinct
    if r.name.trim.isEmpty then IO.pure(Left(Fail.Invalid("名前を入力してください")))
    else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then IO.pure(Left(Fail.Invalid("スコープは read / write / admin から選んでください")))
    else adminScope.filter(_ => scopes.contains(ApiScope.Admin)) match
      case Some(f) => IO.pure(Left(f))
      case None =>
        val (key, prefix) = ApiKeys.generate()
        val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
        users.createKey(userId, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).map(info => Right(CreatedApiKeyDto(key, info)))

/** 日付の入力（yyyy-MM-dd）。 */
object Dates:
  def parse(s: Option[String], what: String): Either[Fail, Option[LocalDate]] =
    s.map(_.trim).filter(_.nonEmpty) match
      case None    => Right(None)
      case Some(v) => scala.util.Try(LocalDate.parse(v)).toOption.toRight(Fail.Invalid(s"$what は yyyy-MM-dd で指定してください")).map(Some(_))
