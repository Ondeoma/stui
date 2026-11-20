package stui.api

import cats.effect.*
import org.http4s.*
import sbirds.auth.Crypto
import sbirds.http.{HttpParts, SessionCookie}
import stui.auth.ApiKeys
import stui.core.*
import stui.db.*

import java.time.Instant
import java.util.UUID

/** 認証: Cookie のセッション、または `Authorization: Bearer <APIキー>`。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig) {

  private val cookie = new SessionCookie(cfg.cookieName, cfg.cookieSecure, cfg.sessionTtl)

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    HttpParts.bearer(req).filter(ApiKeys.looksLike) match {
      case Some(token) =>
        users.findKey(Crypto.sha256(token), Instant.now()).map(_.map {
          (u, gs, kid, scopes, client) =>
            val mcp = req.pathInfo.renderString.startsWith("/mcp") ||
              req.headers.get(org.typelevel.ci.CIString("X-Stui-Mcp")).isDefined
            AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, client, mcp))
        })
      case None =>
        cookie.hash(req) match {
          case None => IO.pure(None)
          case Some(hash) =>
            users.findSession(hash, Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))
        }
    }

  /** 別のユーザーとしての権限確認用（通知の宛先・自動化の実行ユーザー）。無効なユーザーは None。 */
  def sessionFor(userId: UUID, via: AuthVia = AuthVia.Session): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs), via)))

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] =
    cookie.issue((hash, expires) => users.createSession(hash, user.id, expires, idToken))

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO]): IO[Map[String, String]] = cookie.appNames(req)(users.sessionIdToken)

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] = cookie.revoke(req)(users.deleteSession)
}
