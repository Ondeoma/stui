package stui.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Location
import stui.auth.{ApiKeys, Crypto, OidcClient}
import stui.core.*
import stui.db.*
import stui.shared.*

import cats.syntax.all.*
import org.http4s.headers.`Content-Type`
import stui.storage.BlobStore

import java.time.Instant
import java.util.UUID

/** ログイン（OIDC・開発用）・ログアウト。 */
final class AuthRoutes(oidc: Option[OidcClient], users: UserStore, channels: ChannelStore, activity: ActivityStore, val sessions: SessionAuth,
    val access: Access2, cfg: ApiConfig) extends RouteSupport:

  /** ログイン後に戻る先（同じサイトのパスだけ）。 */
  private def safeReturn(s: Option[String]): String =
    s.filter(localPath).getOrElse("/")

  private def redirect(to: String, cookie: Option[ResponseCookie] = None): IO[Response[IO]] =
    val r = Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))
    IO.pure(cookie.fold(r)(r.addCookie))

  /** ログインした: セッションを発行し、初めてなら自動参加のチャンネルに参加させる。 */
  private val LoginCookie = "stui_login_state"

  private def loggedIn(u: User, via: String, idToken: Option[String] = None): IO[ResponseCookie] =
    channels.listForUser(u.id).flatMap(cs => IO.whenA(cs.isEmpty)(channels.joinAutoChannels(u.id).void)) *>
      sessions.issue(u, idToken).flatTap(_ => activity.audit(Some(u.id), via, "auth.login", None, Json.obj()) *> stui.observe.Metrics.count("stui.logins", "via" -> via))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" =>
      val back = safeReturn(param(req, "returnTo"))
      oidc match
        case Some(o) =>
          val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
          // state をこのブラウザの Cookie にも入れ、戻ってきたときに照合する（他人のログインを踏ませる攻撃を防ぐ）
          users.saveLoginState(state, verifier, nonce, back) *> o.authorizationUri(state, verifier, nonce).flatMap(u =>
            redirect(u.renderString, Some(ResponseCookie(LoginCookie, state, path = Some("/auth"), httpOnly = true, secure = cfg.cookieSecure,
              sameSite = Some(SameSite.Lax), maxAge = Some(900)))))
        case None if cfg.devLogin => redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
        case None                 => err(Status.ServiceUnavailable, "OIDC が設定されていません")

    case req @ GET -> Root / "auth" / "callback" =>
      (oidc, param(req, "code"), param(req, "state")) match
        case (Some(_), _, Some(state)) if !req.cookies.find(_.name == LoginCookie).exists(c => Crypto.constantTimeEquals(c.content, state)) =>
          err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
        case (Some(o), Some(code), Some(state)) =>
          users.takeLoginState(state).flatMap {
            case None => err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
            case Some((verifier, nonce, back)) =>
              o.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  activity.audit(None, "web", "auth.denied", None, Json.obj("error" -> e.getMessage.asJson)) *>
                    err(Status.Unauthorized, s"ログインできませんでした: ${e.getMessage}")
                case Right(id) =>
                  for
                    u <- users.upsert(id.sub, id.name, id.email, id.picture, cfg.timezone)
                    r <- if u.disabled then err(Status.Forbidden, "このユーザーは無効にされています")
                         else users.syncGroups(u.id, id.groups) *> loggedIn(u, "web", id.idToken).flatMap(c => redirect(back, Some(c)))
                  yield r
              }
          }
        case (None, _, _) => err(Status.ServiceUnavailable, "OIDC が設定されていません")
        case _            => err(Status.BadRequest, "code と state が必要です")

    // ログアウト: セッションを消し、IdP にもログアウトを頼む（IdP のログインが残っていると、すぐにログインし直してしまうため）
    case req @ POST -> Root / "auth" / "logout" =>
      sessions.revoke(req).flatMap { (idToken, c) =>
        oidc.flatTraverse(_.logoutUri(idToken, s"${cfg.publicUrl.stripSuffix("/")}/")).flatMap { idp =>
          Ok(Json.obj("redirect" -> idp.fold("/")(_.renderString).asJson)).map(_.addCookie(c))
        }
      }

    // 開発用: 名前だけでログインする（DEV_LOGIN=true のときだけ）
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin =>
      body[Json](req) { j =>
        val c     = j.hcursor
        val name  = c.get[String]("name").getOrElse("").trim
        val admin = c.get[Boolean]("admin").getOrElse(false)
        if name.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else
          for
            u0 <- users.upsert(s"dev:$name", name, c.get[String]("email").toOption.filter(_.contains("@")), None, cfg.timezone)
            _  <- users.update(u0.id, Option.when(admin)(true), None)
            u  <- users.find(u0.id).map(_.getOrElse(u0))
            ck <- loggedIn(u, "web")
            r  <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
          yield r
      }

    case GET -> Root / "api" / "client-config" =>
      Ok(ClientConfigDto(cfg.devLogin && oidc.isEmpty, cfg.version, attachments = true, mail = true, cfg.linkServices.map(_.provider),
        cfg.service("skaka").isDefined, (cfg.maxUploadBytes / 1024 / 1024).toInt))
  }

/** 自分の情報・APIキー・通知の設定、ユーザーの一覧、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(users: UserStore, channels: ChannelStore, activity: ActivityStore, blobs: Option[BlobStore], emoji: EmojiStore,
    val sessions: SessionAuth, val access: Access2) extends RouteSupport:

  private val avatarTypes = Set("image/png", "image/jpeg", "image/gif", "image/webp")
  private val avatarMaxBytes = 2L * 1024 * 1024

  /** アイコンを差し替える（前の画像は消す）。 */
  private def replaceAvatar(uid: UUID, key: Option[String]): IO[Unit] =
    val url = key.map(_ => s"/api/users/$uid/avatar?v=${Crypto.randomToken(6)}")
    users.setAvatar(uid, key, url).flatMap(_.traverse_(old => blobs.traverse_(_.delete(old).handleError(_ => ()))))

  private def me(s: AuthSession): MeDto =
    MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.user.timezone, s.viaString, s.scopeStrings)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => authed(req)(s => Ok(me(s)))

    case req @ PATCH -> Root / "api" / "me" =>
      authed(req)(s => body[UpdateMeReq](req)(r =>
        if r.timezone.exists(tz => scala.util.Try(java.time.ZoneId.of(tz)).isFailure) then err(Status.BadRequest, "タイムゾーンが正しくありません")
        else users.updateMe(s.user.id, r.name, r.timezone) *> NoContent()))

    case req @ GET -> Root / "api" / "me" / "notify-prefs" => authed(req)(s => users.notifyPrefs(s.user.id).flatMap(Ok(_)))

    case req @ PUT -> Root / "api" / "me" / "notify-prefs" =>
      authed(req)(s => body[List[NotifyPrefDto]](req)(ps => users.setNotifyPrefs(s.user.id, ps.take(50)) *> NoContent()))

    // ---------------------------------------------------------------- APIキー（画面からのみ）

    case req @ GET -> Root / "api" / "me" / "api-keys" => sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "me" / "api-keys" =>
      sessionOnly(req)(s => body[CreateApiKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct
        if r.name.trim.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then err(Status.BadRequest, "スコープは read / write / admin から選んでください")
        else if scopes.contains(ApiScope.Admin) && !s.isAdmin then err(Status.Forbidden, "admin スコープは管理者だけが使えます")
        else
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.user.id, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).flatMap { info =>
            activity.audit(Some(s.user.id), "web", "api_key.create", Some(info.id.toString), Json.obj("name" -> info.name.asJson, "scopes" -> scopes.asJson)) *>
              Created(CreatedApiKeyDto(key, info))
          }
      })

    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) =>
      sessionOnly(req)(s => users.revokeKey(s.user.id, id).flatMap {
        case true  => activity.audit(Some(s.user.id), "web", "api_key.revoke", Some(id.toString), Json.obj()) *> NoContent()
        case false => err(Status.NotFound, "APIキーが見つかりません")
      })

    // ---------------------------------------------------------------- ユーザー

    // ---------------------------------------------------------------- アイコン
    case req @ POST -> Root / "api" / "me" / "avatar" =>
      sessionOnly(req)(s => blobs match
        case None => err(Status.ServiceUnavailable, "画像の保存先（S3）が設定されていません")
        case Some(store) =>
          withUpload(req, avatarMaxBytes)(up =>
            if !avatarTypes.contains(up.contentType) then err(Status.BadRequest, "PNG・JPEG・GIF・WebP の画像を選んでください")
            else
              val key = s"avatars/${s.user.id}/${UUID.randomUUID()}"
              store.put(key, up.path, up.contentType) *> replaceAvatar(s.user.id, Some(key)) *>
                users.find(s.user.id).flatMap(u => Ok(Json.obj("avatarUrl" -> u.flatMap(_.avatarUrl).asJson)))))

    case req @ DELETE -> Root / "api" / "me" / "avatar" =>
      sessionOnly(req)(s => replaceAvatar(s.user.id, None) *> NoContent())

    case req @ GET -> Root / "api" / "users" / UuidVar(id) / "avatar" =>
      authed(req)(_ => (users.avatarKey(id), IO.pure(blobs)).tupled.flatMap {
        case (Some(key), Some(store)) => store.get(key).flatMap {
            case None => err(Status.NotFound, "画像が見つかりません")
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(meta.contentType).toOption.filter(m => avatarTypes(s"${m.mainType}/${m.subType}")).getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size)))
                .putHeaders(`Content-Type`(mt), "X-Content-Type-Options" -> "nosniff", "Cache-Control" -> "private, max-age=86400"))
          }
        case _ => err(Status.NotFound, "画像が登録されていません")
      })

    // ---------------------------------------------------------------- カスタム絵文字（リアクション用。システム全体）
    case req @ GET -> Root / "api" / "emoji" => authed(req)(_ => emoji.list.flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "emoji" =>
      sessionOnly(req)(s => (blobs, param(req, "name").map(_.toLowerCase)) match
        case (None, _) => err(Status.ServiceUnavailable, "画像の保存先（S3）が設定されていません")
        case (_, n) if !n.exists(_.matches("^[a-z0-9_+-]{2,32}$")) => err(Status.BadRequest, "名前は半角の英小文字・数字・_ + - の 2〜32 文字にしてください")
        case (Some(store), Some(name)) =>
          withUpload(req, 512L * 1024)(up =>
            if !avatarTypes.contains(up.contentType) then err(Status.BadRequest, "PNG・JPEG・GIF・WebP の画像を選んでください")
            else
              val key = s"emoji/$name-${UUID.randomUUID()}"
              store.put(key, up.path, up.contentType) *> emoji.create(name, key, up.contentType, s.user.id).flatMap {
                case true  => Created(CustomEmojiDto(name, CustomEmoji.url(name), Some(s.user.name)))
                case false => store.delete(key).handleError(_ => ()) *> err(Status.Conflict, s"「:$name:」はすでにあります")
              })
        case _ => err(Status.BadRequest, "name を指定してください"))

    case req @ DELETE -> Root / "api" / "emoji" / name =>
      sessionOnly(req)(s => emoji.find(name).flatMap {
        case None => err(Status.NotFound, "絵文字が見つかりません")
        case Some(e) if !e.createdBy.contains(s.user.id) && !s.isAdmin => err(Status.Forbidden, "登録した人か管理者だけが削除できます")
        case Some(e) => emoji.delete(name) *> blobs.traverse_(_.delete(e.s3Key).handleError(_ => ())) *> NoContent()
      })

    case req @ GET -> Root / "api" / "emoji" / name / "image" =>
      authed(req)(_ => (emoji.find(name), IO.pure(blobs)).tupled.flatMap {
        case (Some(e), Some(store)) => store.get(e.s3Key).flatMap {
            case None => err(Status.NotFound, "画像が見つかりません")
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(e.contentType).toOption.getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size)))
                .putHeaders(`Content-Type`(mt), "X-Content-Type-Options" -> "nosniff", "Cache-Control" -> "private, max-age=86400"))
          }
        case _ => err(Status.NotFound, "絵文字が見つかりません")
      })

    case req @ GET -> Root / "api" / "users" =>
      authed(req)(_ => users.list(param(req, "q"), includeDisabled = false, intParam(req, "limit", 50, 500)).flatMap(us =>
        Ok(us.map(u => UserBrief(u.id, u.name, u.avatarUrl, u.bot)))))

    case req @ GET -> Root / "api" / "users" / UuidVar(id) =>
      authed(req)(_ => users.find(id).flatMap {
        case None => err(Status.NotFound, "ユーザーが見つかりません")
        case Some(u) => Ok(Json.obj("id" -> u.id.asJson, "name" -> u.name.asJson, "email" -> u.email.asJson, "avatarUrl" -> u.avatarUrl.asJson,
            "bot" -> u.isBot.asJson, "disabled" -> u.disabled.asJson, "timezone" -> u.timezone.asJson))
      })

    case req @ GET -> Root / "api" / "admin" / "channels" => admin(req)(s => channels.all(s.user.id).flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "admin" / "users" =>
      admin(req)(_ => users.list(param(req, "q"), includeDisabled = true, 2000).flatMap(Ok(_)))

    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) =>
      admin(req)(s => body[UpdateUserReq](req) { r =>
        if id == s.user.id && (r.isAdmin.contains(false) || r.disabled.contains(true)) then err(Status.BadRequest, "自分自身の管理者権限は外せません")
        else users.update(id, r.isAdmin, r.disabled).flatMap {
          case true  => activity.audit(Some(s.user.id), s.viaString, "user.update", Some(id.toString), r.asJson) *> NoContent()
          case false => err(Status.NotFound, "ユーザーが見つかりません")
        }
      })

    case req @ GET -> Root / "api" / "admin" / "audit" =>
      admin(req)(_ => activity.auditLog(param(req, "before").flatMap(_.toLongOption), param(req, "action"), intParam(req, "limit", 100, 500)).flatMap(Ok(_)))
  }
