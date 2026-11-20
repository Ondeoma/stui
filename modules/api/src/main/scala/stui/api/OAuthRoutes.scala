package stui.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import sbirds.auth.Crypto
import sbirds.oauth.{OAuthClient, OAuthCode, OAuthKeyStore, OAuthProvider}
import stui.auth.ApiKeys
import stui.core.AuthSession
import stui.db.*
import stui.shared.Html

import java.util.UUID

/**
 * 兄弟のサービス（skea など）への認可（OAuth 2.0 の認可コード + PKCE。流れは sbirds.oauth.OAuthProvider）。
 * 発行するトークンは、そのユーザーの API キー（「<クライアント名> 連携」。スコープはクライアントに許したもののうち求められたもの。
 * API キーの画面から失効できる）。連携サービスはこのトークンで通知を送り（POST /api/apps/notify）、メッセージの概要を読む。
 */
final class OAuthRoutes(oauth: OAuthKeyStore, activity: ActivityStore, sessions: SessionAuth, cfg: ApiConfig) {

  private val host = new OAuthProvider.Host[AuthSession] {
    val settings = OAuthProvider.Settings("stui", cfg.cookieSecure)
    val codes = oauth

    def client(id: String): IO[Option[OAuthClient]] = IO.pure(cfg.oauthClients.find(_.id == id))

    // API キーでは許可できない（同意の画面を経ずにスコープを広げられるため）
    def browserSession(req: Request[IO]): IO[Option[AuthSession]] = sessions.fromRequest(req).map(_.filter(_.isSession))

    def userId(s: AuthSession): UUID = s.user.id

    def consent(req: Request[IO], c: OAuthClient, s: AuthSession, scopes: List[String]): IO[OAuthProvider.Consent] =
      sessions.appNames(req).map { names =>
        // 名前は sruru に登録した表示名（なければ OAUTH_CLIENTS の名前）
        val name = Html.escape(names.get(c.id).filter(_.trim.nonEmpty).getOrElse(c.name))
        val self = Html.escape(names.get(sbirds.auth.AppNames.SelfKey).filter(_.trim.nonEmpty).getOrElse("stui"))
        val items =
          if scopes.contains("write") then
            "<li>あなた宛ての通知を、ボット（" + name + "）からの DM として送ります</li><li>あなたが参加しているチャンネルに、あなたの指定で投稿することがあります</li>"
          else "<li>読み取りだけです（投稿・変更・削除はしません）</li>"
        OAuthProvider.Consent(
          s"$name との連携",
          s"""<h1>$name との連携</h1>
             |<p><b>$name</b> が、あなた（${Html.escape(s.user.name)}）の権限で $self を使います。</p>
             |<ul class="muted">$items<li>あなたが見られるチャンネル・メッセージだけが対象です</li>
             |<li>個人設定の「APIキー」から、いつでも連携をやめられます</li></ul>""".stripMargin
        )
      }

    override def authorized(req: Request[IO], s: AuthSession, c: OAuthClient, scopes: List[String]): IO[Unit] =
      activity.audit(Some(s.user.id), "web", "oauth.authorize", Some(c.id), Json.obj())

    def issue(req: Request[IO], c: OAuthClient, oc: OAuthCode): IO[OAuthProvider.Token] = {
      val (key, prefix) = ApiKeys.generate()
      oauth.replaceKey(
        oc.userId,
        c.id,
        s"${c.name} 連携",
        prefix,
        Crypto.sha256(key),
        oc.scope.split(' ').toList.filter(_.nonEmpty)
      ) *>
        activity.audit(Some(oc.userId), "oauth", "api_key.create", Some(c.id), Json.obj("client" -> c.id.asJson))
          .as(OAuthProvider.Token(key, oc.scope))
    }

    def revoke(req: Request[IO], c: OAuthClient, token: String): IO[Unit] =
      oauth.revokeKey(Crypto.sha256(token), c.id)
  }

  val routes: HttpRoutes[IO] = new OAuthProvider(host).routes
}
