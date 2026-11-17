package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{`Content-Type`, Authorization, Location}
import stui.auth.{ApiKeys, Crypto}
import stui.db.*
import stui.shared.*

import java.time.Instant

/**
 * 兄弟のサービス（skea など）への認可（OAuth 2.0 の認可コード + PKCE。RFC 6749・7636・7009）。
 * 発行するトークンは、そのユーザーの API キー（「<クライアント名> 連携」。スコープはクライアントに許したもののうち求められたもの。
 * API キーの画面から失効できる）。連携サービスはこのトークンで通知を送り（POST /api/apps/notify）、メッセージの概要を読む。
 */
final class OAuthRoutes(oauth: OAuthStore, activity: ActivityStore, val sessions: SessionAuth, val access: Access2, cfg: ApiConfig)
    extends RouteSupport:

  private val CodeTtlSeconds = 60L
  private val CsrfCookie     = "stui_oauth_csrf"

  private def client(id: String): Option[OAuthClient] = cfg.oauthClients.find(_.id == id)

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  /** redirect_uri に結果を付けて戻す。 */
  private def back(uri: String, params: (String, Option[String])*): IO[Response[IO]] =
    val q = params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }.mkString("&")
    IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(uri + (if uri.contains("?") then "&" else "?") + q))))

  // ---------------------------------------------------------------- 許可の画面（サーバーで HTML を組み立てる）

  /**
   * 許可の画面。CSP の form-action は付けない（フォームの送信後のリダイレクトの連鎖にも効くため、
   * 連携元が callback の後に別の origin へ戻すと止まる）。CSRF は Cookie と hidden の値の照合で防ぐ。
   */
  private def page(title: String, body: String): IO[Response[IO]] =
    val html = s"""<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      |<title>${Html.escape(title)} | stui</title><style>
      |:root{color-scheme:light dark}body{margin:0;font-family:system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;background:#f1f5f9;color:#1e293b;
      |display:flex;min-height:100vh;align-items:center;justify-content:center;padding:1rem}
      |@media(prefers-color-scheme:dark){body{background:#0f172a;color:#e2e8f0}.card{background:#1c273d!important;border-color:#283548!important}}
      |.card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:1.6rem 1.8rem;max-width:440px;width:100%;
      |box-shadow:0 11px 15px -7px rgba(0,0,0,.2),0 24px 38px 3px rgba(0,0,0,.14)}
      |h1{font-size:1.15rem;margin:0 0 .8rem}p{line-height:1.7;margin:.4rem 0}.muted{color:#64748b;font-size:.85rem}.logo{color:#2563eb;font-weight:800}
      |.actions{display:flex;gap:.6rem;justify-content:flex-end;margin-top:1.2rem}button{font:inherit;border-radius:8px;padding:.5rem 1.1rem;cursor:pointer;
      |border:1px solid #cbd5e1;background:transparent;color:inherit}button.primary{background:#2563eb;border-color:#2563eb;color:#fff}
      |ul{padding-left:1.2rem;margin:.4rem 0}</style></head><body><div class="card"><div class="logo">stui</div>$body</div></body></html>""".stripMargin
    IO.pure(Response[IO](Status.Ok).withEntity(html)(using EntityEncoder.stringEncoder).putHeaders(`Content-Type`(MediaType.text.html, Charset.`UTF-8`),
      "Cache-Control" -> "no-store", "X-Frame-Options" -> "DENY", "Content-Security-Policy" -> "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"))

  private def errorPage(msg: String): IO[Response[IO]] =
    page("認可できません", s"<h1>認可できません</h1><p>${Html.escape(msg)}</p><p class=\"muted\">連携元のサービスの設定を確認してください。</p>").map(_.withStatus(Status.BadRequest))

  /** 認可の要求の確認（client_id・redirect_uri が不正ならリダイレクトしない）。 */
  /** 求められたスコープ（なければ read）。クライアントに許していないものが含まれていれば None。 */
  private def scopes(c: OAuthClient, requested: Option[String]): Option[List[String]] =
    val rs = requested.map(_.split(' ').map(_.trim).filter(_.nonEmpty).toList.distinct).filter(_.nonEmpty).getOrElse(List("read"))
    Option.when(rs.forall(c.scopes.contains))(rs)

  private def validate(p: String => Option[String]): Either[IO[Response[IO]], (OAuthClient, String, Option[String], String)] =
    (p("client_id").flatMap(client), p("redirect_uri")) match
      case (None, _) => Left(errorPage("登録されていないクライアントです（client_id）"))
      case (Some(c), r) if !r.contains(c.redirectUri) => Left(errorPage("戻り先（redirect_uri）が登録されたものと一致しません"))
      case (Some(c), _) =>
        val state = p("state")
        if !p("response_type").contains("code") then Left(back(c.redirectUri, "error" -> Some("unsupported_response_type"), "state" -> state))
        else if !p("code_challenge_method").contains("S256") || !p("code_challenge").exists(_.matches("^[A-Za-z0-9_-]{43}$")) then
          Left(back(c.redirectUri, "error" -> Some("invalid_request"), "error_description" -> Some("PKCE（S256）が必要です"), "state" -> state))
        else if scopes(c, p("scope")).isEmpty then Left(back(c.redirectUri, "error" -> Some("invalid_scope"), "state" -> state))
        else Right((c, c.redirectUri, state, p("code_challenge").get))

  // ---------------------------------------------------------------- トークンの発行・失効（クライアント認証は Basic）

  private def oauthError(status: Status, code: String, desc: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(Json.obj("error" -> code.asJson, "error_description" -> desc.asJson))
      .putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache").putHeaders(
        (if status == Status.Unauthorized then List[Header.ToRaw]("WWW-Authenticate" -> "Basic realm=\"stui\"") else Nil)*))

  private def basicClient(req: Request[IO]): Option[OAuthClient] =
    req.headers.get[Authorization].collect { case Authorization(BasicCredentials(id, secret)) => (id, secret) }
      .flatMap((id, secret) => client(id).filter(c => Crypto.constantTimeEquals(c.secret, secret)))

  private def form(req: Request[IO])(f: Map[String, String] => IO[Response[IO]]): IO[Response[IO]] =
    req.as[UrlForm].attempt.flatMap {
      case Left(_)  => oauthError(Status.BadRequest, "invalid_request", "application/x-www-form-urlencoded で送ってください")
      case Right(u) => f(u.values.view.mapValues(_.headOption.getOrElse("")).toMap)
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "oauth" / "authorize" =>
      validate(param(req, _)) match
        case Left(r) => r
        case Right((c, _, _, _)) =>
          sessions.fromRequest(req).map(_.filter(_.isSession)).flatMap { // API キーでは許可できない（同意の画面を経ずにスコープを広げられるため）
            case None =>
              // ログインしてからこの画面に戻る
              val here = req.uri.renderString
              IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(s"/auth/login?returnTo=${enc(here)}"))))
            case Some(s) =>
              val csrf = Crypto.randomToken(24)
              val hidden = List("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "response_type", "scope")
                .flatMap(k => param(req, k).map(v => s"""<input type="hidden" name="$k" value="${Html.escape(v)}">""")).mkString
              val name = Html.escape(c.name)
              val write = scopes(c, param(req, "scope")).exists(_.contains("write"))
              val items =
                if write then "<li>あなた宛ての通知を、ボット（" + name + "）からの DM として送ります</li><li>あなたが参加しているチャンネルに、あなたの指定で投稿することがあります</li>"
                else "<li>読み取りだけです（投稿・変更・削除はしません）</li>"
              page(s"$name との連携", s"""<h1>$name との連携</h1>
                |<p><b>$name</b> が、あなた（${Html.escape(s.user.name)}）の権限で stui を使います。</p>
                |<ul class="muted">$items<li>あなたが見られるチャンネル・メッセージだけが対象です</li>
                |<li>個人設定の「APIキー」から、いつでも連携をやめられます</li></ul>
                |<form method="post" action="/oauth/authorize">$hidden<input type="hidden" name="csrf" value="$csrf">
                |<div class="actions"><button type="submit" name="decision" value="deny">拒否する</button>
                |<button type="submit" name="decision" value="allow" class="primary">許可する</button></div></form>""".stripMargin)
                .map(_.addCookie(ResponseCookie(CsrfCookie, csrf, path = Some("/oauth"), httpOnly = true, secure = cfg.cookieSecure,
                  sameSite = Some(SameSite.Strict), maxAge = Some(600))))
          }

    case req @ POST -> Root / "oauth" / "authorize" =>
      req.as[UrlForm].flatMap { u =>
        val p = (k: String) => u.getFirst(k).map(_.trim).filter(_.nonEmpty)
        validate(p) match
          case Left(r) => r
          case Right((c, redirect, state, challenge)) =>
            val csrfOk = req.cookies.find(_.name == CsrfCookie).exists(ck => p("csrf").exists(v => Crypto.constantTimeEquals(ck.content, v)))
            sessions.fromRequest(req).map(_.filter(_.isSession)).flatMap {
              case None => errorPage("ログインが切れました。連携元の画面からやり直してください")
              case Some(_) if !csrfOk => errorPage("画面の有効期限が切れました。連携元の画面からやり直してください")
              case Some(s) if p("decision").contains("allow") =>
                val code = Crypto.randomToken(32)
                val scope = scopes(c, p("scope")).getOrElse(List("read")).mkString(" ")
                oauth.createCode(Crypto.sha256(code), OAuthCode(c.id, redirect, s.user.id, challenge, scope), Instant.now().plusSeconds(CodeTtlSeconds)) *>
                  activity.audit(Some(s.user.id), "web", "oauth.authorize", Some(c.id), Json.obj()) *>
                  back(redirect, "code" -> Some(code), "state" -> state)
              case Some(_) => back(redirect, "error" -> Some("access_denied"), "state" -> state)
            }.map(_.removeCookie(ResponseCookie(CsrfCookie, "", path = Some("/oauth"))))
      }

    case req @ POST -> Root / "oauth" / "token" =>
      basicClient(req) match
        case None => oauthError(Status.Unauthorized, "invalid_client", "クライアントの認証に失敗しました")
        case Some(c) =>
          form(req) { f =>
            (f.get("grant_type"), f.get("code"), f.get("redirect_uri"), f.get("code_verifier")) match
              case (g, _, _, _) if !g.contains("authorization_code") =>
                oauthError(Status.BadRequest, if g.isEmpty then "invalid_request" else "unsupported_grant_type", "grant_type は authorization_code です")
              case (_, Some(code), Some(uri), Some(verifier)) if verifier.length >= 43 && verifier.length <= 128 =>
                oauth.consumeCode(Crypto.sha256(code)).flatMap {
                  case Some(oc) if oc.clientId == c.id && oc.redirectUri == uri && Crypto.constantTimeEquals(oc.codeChallenge, Crypto.pkceChallenge(verifier)) =>
                    val (key, prefix) = ApiKeys.generate()
                    oauth.replaceKey(oc.userId, c.id, s"${c.name} 連携", prefix, Crypto.sha256(key), oc.scope.split(' ').toList.filter(_.nonEmpty)) *>
                      activity.audit(Some(oc.userId), "oauth", "api_key.create", Some(c.id), Json.obj("client" -> c.id.asJson)) *>
                      Ok(Json.obj("access_token" -> key.asJson, "token_type" -> "Bearer".asJson, "scope" -> oc.scope.asJson))
                        .map(_.putHeaders("Cache-Control" -> "no-store", "Pragma" -> "no-cache"))
                  case _ => oauthError(Status.BadRequest, "invalid_grant", "認可コードが無効です（期限切れ・使用済み・不一致）")
                }
              case _ => oauthError(Status.BadRequest, "invalid_request", "code・redirect_uri・code_verifier（43〜128文字）を指定してください")
          }

    case req @ POST -> Root / "oauth" / "revoke" =>
      basicClient(req) match
        case None => oauthError(Status.Unauthorized, "invalid_client", "クライアントの認証に失敗しました")
        case Some(c) => form(req)(f => f.get("token").filter(_.nonEmpty).traverse_(t => oauth.revokeKey(Crypto.sha256(t), c.id)) *> Ok(Json.obj()))

  }
