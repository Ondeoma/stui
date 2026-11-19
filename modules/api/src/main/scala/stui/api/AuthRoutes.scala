package stui.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Location
import sbirds.auth.{Crypto, LoginResult, OidcClient}
import stui.core.*
import stui.db.*
import stui.shared.*

import cats.syntax.all.*

import java.time.Instant

/** ログイン（OIDC・開発用）・ログアウト。 */
final class AuthRoutes(
  oidc: Option[OidcClient],
  users: UserStore,
  channels: ChannelStore,
  activity: ActivityStore,
  val sessions: SessionAuth,
  val access: Access2,
  cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" => getAuthLogin(req)
    case req @ GET -> Root / "auth" / "callback" => getAuthCallback(req)
    case req @ POST -> Root / "auth" / "logout" => postAuthLogout(req)
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin => postAuthDevLogin(req)
    case GET -> Root / "api" / "client-config" => getClientConfig()
  }

  private def getAuthLogin(
    req: Request[IO]
  ): IO[Response[IO]] = {
    val back = safeReturn(param(req, "returnTo"))
    oidc match {
      case Some(o) =>
        val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
        // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する（他人のログインを踏ませる攻撃を防ぐ）
        users.saveLoginState(state, verifier, nonce, back) *> o.authorizationUri(state, verifier, nonce).flatMap(u =>
          redirect(
            u.renderString,
            Some(ResponseCookie(
              LoginCookie,
              state,
              path = Some("/auth"),
              httpOnly = true,
              secure = cfg.cookieSecure,
              sameSite = Some(SameSite.Lax),
              maxAge = Some(900)
            ))
          )
        )
      case None if cfg.devLogin => redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
      case None => err(ErrorCode.OidcNotConfigured)
    }
  }

  private def getAuthCallback(
    req: Request[IO]
  ): IO[Response[IO]] =
    (oidc, param(req, "code"), param(req, "state")) match {
      case (Some(_), _, Some(state))
          if !req.cookies.find(_.name == LoginCookie).exists(c => Crypto.constantTimeEquals(c.content, state)) =>
        err(ErrorCode.LoginExpired)
      case (Some(o), Some(code), Some(state)) =>
        users.takeLoginState(state).flatMap {
          case None => err(ErrorCode.LoginExpired)
          case Some((verifier, nonce, back)) =>
            o.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
              case Left(e) =>
                activity.audit(None, "web", "auth.denied", None, Json.obj("error" -> e.getMessage.asJson)) *>
                  err(ErrorCode.LoginFailed, s"ログインできませんでした: ${e.getMessage}")
              case Right(LoginResult(id, _)) if AgentAccounts.reservedSub(id.sub) =>
                // ボット・エージェント専用アカウントの sub ではログインさせない
                activity.audit(None, "web", "auth.denied", None, Json.obj("error" -> "reserved sub".asJson)) *>
                  err(ErrorCode.LoginAccountDenied)
              case Right(LoginResult(id, idToken)) =>
                for {
                  u <- users.upsert(id.sub, id.name, id.email, id.picture, cfg.timezone)
                  r <- if u.disabled then err(ErrorCode.UserDisabled)
                  else
                    users.syncGroups(u.id, id.groups) *>
                      loggedIn(u, "web", Some(idToken)).flatMap(c => redirect(back, Some(c)))
                } yield r
            }
        }
      case (None, _, _) => err(ErrorCode.OidcNotConfigured)
      case _ => err(ErrorCode.LoginCodeStateRequired)
    }

  private def postAuthLogout(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessions.revoke(req).flatMap {
      (
        idToken,
        c
      ) =>
        oidc.flatTraverse(_.logoutUri(idToken, s"${cfg.publicUrl.stripSuffix("/")}/")).flatMap { idp =>
          Ok(Json.obj("redirect" -> idp.fold("/")(_.renderString).asJson)).map(_.addCookie(c))
        }
    }

  private def postAuthDevLogin(
    req: Request[IO]
  ): IO[Response[IO]] =
    body[Json](req) { j =>
      val c = j.hcursor
      val name = c.get[String]("name").getOrElse("").trim
      val admin = c.get[Boolean]("admin").getOrElse(false)
      if name.isEmpty then err(ErrorCode.NameRequired)
      else
        for {
          u0 <- users.upsert(
            s"dev:$name",
            name,
            c.get[String]("email").toOption.filter(_.contains("@")),
            None,
            cfg.timezone
          )
          _ <- users.update(u0.id, Option.when(admin)(true), None)
          u <- users.find(u0.id).map(_.getOrElse(u0))
          ck <- loggedIn(u, "web")
          r <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
        } yield r
    }

  private def getClientConfig(): IO[Response[IO]] =
    Ok(ClientConfigDto(
      cfg.devLogin && oidc.isEmpty,
      cfg.version,
      attachments = true,
      mail = true,
      cfg.linkServices.map(_.provider),
      cfg.service("skaka").isDefined,
      (cfg.maxUploadBytes / 1024 / 1024).toInt
    ))

  /** ログイン後に戻る先（同じサイトのパスだけ）。 */
  private def safeReturn(
    s: Option[String]
  ): String =
    s.filter(localPath).getOrElse("/")

  private def redirect(
    to: String,
    cookie: Option[ResponseCookie] = None
  ): IO[Response[IO]] = {
    val r = Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))
    IO.pure(cookie.fold(r)(r.addCookie))
  }

  /** ログインした: セッションを発行し、初めてなら自動参加のチャンネルに参加させる。 */
  private val LoginCookie = "stui_login_state"

  private def loggedIn(
    u: User,
    via: String,
    idToken: Option[String] = None
  ): IO[ResponseCookie] =
    channels.listForUser(u.id).flatMap(cs => IO.whenA(cs.isEmpty)(channels.joinAutoChannels(u.id).void)) *>
      sessions.issue(u, idToken).flatTap(_ =>
        activity.audit(Some(u.id), via, "auth.login", None, Json.obj()) *>
          sbirds.observe.Metrics.count("stui.logins", "via" -> via)
      )

}
