package stui.api

import sbirds.shared.AppError

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import stui.core.*
import stui.db.*
import stui.shared.*

import cats.syntax.all.*
import org.http4s.headers.`Content-Type`
import sbirds.storage.BlobStore

import java.util.UUID

/** 自分の情報・APIキー・通知の設定、ユーザーの一覧、管理画面（ユーザー・監査ログ）。 */
final class UserRoutes(
  users: UserStore,
  channels: ChannelStore,
  activity: ActivityStore,
  blobs: Option[BlobStore],
  emoji: EmojiStore,
  val sessions: SessionAuth,
  val access: Access2) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => getMe(req)
    case req @ PATCH -> Root / "api" / "me" => patchMe(req)
    case req @ GET -> Root / "api" / "me" / "notify-prefs" => getMeNotifyPrefs(req)
    case req @ PUT -> Root / "api" / "me" / "notify-prefs" => putMeNotifyPrefs(req)
    case req @ GET -> Root / "api" / "me" / "api-keys" => getMeApiKeys(req)
    case req @ POST -> Root / "api" / "me" / "api-keys" => postMeApiKeys(req)
    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) => deleteMeApiKey(req, id)
    case req @ POST -> Root / "api" / "me" / "avatar" => postMeAvatar(req)
    case req @ DELETE -> Root / "api" / "me" / "avatar" => deleteMeAvatar(req)
    case req @ GET -> Root / "api" / "users" / UuidVar(id) / "avatar" => getUserAvatar(req, id)
    case req @ GET -> Root / "api" / "emoji" => getEmojis(req)
    case req @ POST -> Root / "api" / "emoji" => postEmoji(req)
    case req @ DELETE -> Root / "api" / "emoji" / name => deleteEmoji(req, name)
    case req @ GET -> Root / "api" / "emoji" / name / "image" => getEmojiImage(req, name)
    case req @ GET -> Root / "api" / "users" => getUsers(req)
    case req @ GET -> Root / "api" / "users" / UuidVar(id) => getUser(req, id)
    case req @ GET -> Root / "api" / "admin" / "channels" => getAdminChannels(req)
    case req @ GET -> Root / "api" / "admin" / "users" => getAdminUsers(req)
    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) => patchAdminUser(req, id)
    case req @ GET -> Root / "api" / "admin" / "audit" => getAdminAudit(req)
  }

  private def getMe(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => sessions.appNames(req).flatMap(n => Ok(me(s).copy(appNames = n))))

  private def patchMe(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[UpdateMeReq](req)(r =>
        if r.timezone.exists(tz => scala.util.Try(java.time.ZoneId.of(tz)).isFailure) then
          err(ErrorCode.TimezoneInvalid)
        else users.updateMe(s.user.id, r.name, r.timezone) *> NoContent()
      )
    )

  private def getMeNotifyPrefs(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => users.notifyPrefs(s.user.id).flatMap(Ok(_)))

  private def putMeNotifyPrefs(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[List[NotifyPrefDto]](req)(ps => users.setNotifyPrefs(s.user.id, ps.take(50)) *> NoContent())
    )

  private def getMeApiKeys(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessionOnly(req)(s => users.keys(s.user.id).flatMap(Ok(_)))

  private def postMeApiKeys(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      body[CreateApiKeyReq](req)(r =>
        respond(ApiKeyIssue(
          users,
          s.user.id,
          r,
          Option.unless(s.isAdmin)(AppError(ErrorCode.ApiKeyAdminScopeAdminOnly))
        )) {
          c =>
            activity.audit(
              Some(s.user.id),
              "web",
              "api_key.create",
              Some(c.info.id.toString),
              Json.obj("name" -> c.info.name.asJson, "scopes" -> c.info.scopes.asJson)
            ) *>
              Created(c)
        }
      )
    )

  private def deleteMeApiKey(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      users.revokeKey(s.user.id, id).flatMap {
        case true => activity.audit(Some(s.user.id), "web", "api_key.revoke", Some(id.toString), Json.obj()) *>
            NoContent()
        case false => err(ErrorCode.ApiKeyNotFound)
      }
    )

  private def postMeAvatar(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      blobs match {
        case None => err(ErrorCode.StorageNotConfigured, "画像の保存先（S3）が設定されていません")
        case Some(store) =>
          withUpload(req, avatarMaxBytes)(up =>
            if !avatarTypes.contains(up.contentType) then err(ErrorCode.ImageTypeUnsupported)
            else {
              val key = s"avatars/${s.user.id}/${UUID.randomUUID()}"
              store.put(key, up.path, up.contentType) *> replaceAvatar(s.user.id, Some(key)) *>
                users.find(s.user.id).flatMap(u => Ok(Json.obj("avatarUrl" -> u.flatMap(_.avatarUrl).asJson)))
            }
          )
      }
    )

  private def deleteMeAvatar(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessionOnly(req)(s => replaceAvatar(s.user.id, None) *> NoContent())

  private def getUserAvatar(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(_ =>
      (users.avatarKey(id), IO.pure(blobs)).tupled.flatMap {
        case (Some(key), Some(store)) => store.get(key).flatMap {
            case None => err(ErrorCode.ImageNotFound)
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(meta.contentType).toOption.filter(m =>
                avatarTypes(s"${m.mainType}/${m.subType}")
              ).getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size)))
                .putHeaders(
                  `Content-Type`(mt),
                  "X-Content-Type-Options" -> "nosniff",
                  "Cache-Control" -> "private, max-age=86400"
                ))
          }
        case _ => err(ErrorCode.ImageNotFound, "画像が登録されていません")
      }
    )

  private def getEmojis(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(_ => emoji.list.flatMap(Ok(_)))

  private def postEmoji(
    req: Request[IO]
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      (blobs, param(req, "name").map(_.toLowerCase)) match {
        case (None, _) => err(ErrorCode.StorageNotConfigured, "画像の保存先（S3）が設定されていません")
        case (_, n) if !n.exists(_.matches("^[a-z0-9_+-]{2,32}$")) =>
          err(ErrorCode.EmojiNameInvalid)
        case (Some(store), Some(name)) =>
          withUpload(req, 512L * 1024)(up =>
            if !avatarTypes.contains(up.contentType) then err(ErrorCode.ImageTypeUnsupported)
            else {
              val key = s"emoji/$name-${UUID.randomUUID()}"
              store.put(key, up.path, up.contentType) *> emoji.create(name, key, up.contentType, s.user.id).flatMap {
                case true => Created(CustomEmojiDto(name, CustomEmoji.url(name), Some(s.user.name)))
                case false => store.delete(key).handleError(_ => ()) *>
                    err(ErrorCode.EmojiNameTaken, s"「:$name:」はすでにあります")
              }
            }
          )
        case _ => err(ErrorCode.EmojiNameRequired)
      }
    )

  private def deleteEmoji(
    req: Request[IO],
    name: String
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      emoji.find(name).flatMap {
        case None => err(ErrorCode.EmojiNotFound)
        case Some(e) if !e.createdBy.contains(s.user.id) && !s.isAdmin => err(ErrorCode.EmojiDeleteNotAllowed)
        case Some(e) => emoji.delete(name) *> blobs.traverse_(_.delete(e.s3Key).handleError(_ => ())) *> NoContent()
      }
    )

  private def getEmojiImage(
    req: Request[IO],
    name: String
  ): IO[Response[IO]] =
    authed(req)(_ =>
      (emoji.find(name), IO.pure(blobs)).tupled.flatMap {
        case (Some(e), Some(store)) => store.get(e.s3Key).flatMap {
            case None => err(ErrorCode.ImageNotFound)
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(e.contentType).toOption.getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size)))
                .putHeaders(
                  `Content-Type`(mt),
                  "X-Content-Type-Options" -> "nosniff",
                  "Cache-Control" -> "private, max-age=86400"
                ))
          }
        case _ => err(ErrorCode.EmojiNotFound)
      }
    )

  private def getUsers(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(_ =>
      users.list(param(req, "q"), includeDisabled = false, intParam(req, "limit", 50, 500)).flatMap(us =>
        Ok(us.map(u => UserBrief(u.id, u.name, u.avatarUrl, u.bot)))
      )
    )

  private def getUser(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(_ =>
      users.find(id).flatMap {
        case None => err(ErrorCode.UserNotFound)
        case Some(u) => Ok(Json.obj(
            "id" -> u.id.asJson,
            "name" -> u.name.asJson,
            "email" -> u.email.asJson,
            "avatarUrl" -> u.avatarUrl.asJson,
            "bot" -> u.isBot.asJson,
            "disabled" -> u.disabled.asJson,
            "timezone" -> u.timezone.asJson
          ))
      }
    )

  private def getAdminChannels(
    req: Request[IO]
  ): IO[Response[IO]] =
    admin(req)(s => channels.all(s.user.id).flatMap(Ok(_)))

  private def getAdminUsers(
    req: Request[IO]
  ): IO[Response[IO]] =
    admin(req)(_ => users.list(param(req, "q"), includeDisabled = true, 2000).flatMap(Ok(_)))

  private def patchAdminUser(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    admin(req)(s =>
      body[UpdateUserReq](req) { r =>
        if id == s.user.id && (r.isAdmin.contains(false) || r.disabled.contains(true)) then
          err(ErrorCode.AdminSelfDemote)
        else
          users.update(id, r.isAdmin, r.disabled).flatMap {
            case true => activity.audit(Some(s.user.id), s.viaString, "user.update", Some(id.toString), r.asJson) *>
                NoContent()
            case false => err(ErrorCode.UserNotFound)
          }
      }
    )

  private def getAdminAudit(
    req: Request[IO]
  ): IO[Response[IO]] =
    admin(req)(_ =>
      activity.auditLog(
        param(req, "before").flatMap(_.toLongOption),
        param(req, "action"),
        intParam(req, "limit", 100, 500)
      ).flatMap(Ok(_))
    )

  private val avatarTypes = Set("image/png", "image/jpeg", "image/gif", "image/webp")
  private val avatarMaxBytes = 2L * 1024 * 1024

  /** アイコンを差し替える（前の画像は消す）。 */
  private def replaceAvatar(
    uid: UUID,
    key: Option[String]
  ): IO[Unit] = {
    val url = key.map(_ => s"/api/users/$uid/avatar?v=${Crypto.randomToken(6)}")
    users.setAvatar(uid, key, url).flatMap(_.traverse_(old => blobs.traverse_(_.delete(old).handleError(_ => ()))))
  }

  private def me(
    s: AuthSession
  ): MeDto =
    MeDto(
      s.user.id,
      s.user.name,
      s.user.email,
      s.user.avatarUrl,
      s.isAdmin,
      s.user.timezone,
      s.viaString,
      s.scopeStrings
    )

}
