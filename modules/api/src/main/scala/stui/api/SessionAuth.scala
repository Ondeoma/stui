package stui.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import stui.auth.ApiKeys
import stui.core.*
import stui.db.*

import java.time.Instant
import java.util.UUID

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig) {

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    bearer(req) match {
      case Some(token) =>
        users.findKey(Crypto.sha256(token), Instant.now()).map(_.map {
          (u, gs, kid, scopes, client) =>
            val mcp = req.pathInfo.renderString.startsWith("/mcp") ||
              req.headers.get(org.typelevel.ci.CIString("X-Stui-Mcp")).isDefined
            AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, client, mcp))
        })
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match {
          case None => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) =>
              AuthSession(u, gs, isAdmin(u, gs))
            ))
        }
    }

  /** 別のユーザーとしての権限確認用（通知の宛先・自動化の実行ユーザー）。無効なユーザーは None。 */
  def sessionFor(userId: UUID, via: AuthVia = AuthVia.Session): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs), via)))

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(
      ApiKeys.looksLike
    )

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    users.createSession(
      Crypto.sha256(token),
      user.id,
      Instant.now().plusSeconds(cfg.sessionTtl.toSeconds),
      idToken
    ).as(cookie(token, cfg.sessionTtl.toSeconds))
  }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO]): IO[Map[String, String]] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) =>
        users.sessionIdToken(Crypto.sha256(c.content)).map(_.fold(Map.empty)(sbirds.auth.AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None => IO.pure(None -> cookie("", 0))
    }

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(
      cfg.cookieName,
      value,
      maxAge = Some(maxAge),
      path = Some("/"),
      httpOnly = true,
      secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax)
    )
}
