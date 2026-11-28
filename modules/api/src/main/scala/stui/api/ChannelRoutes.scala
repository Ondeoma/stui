package stui.api

import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.util.UUID

/** チャンネル・DM・メンバー・ブックマーク。 */
final class ChannelRoutes(channels: ChannelStore,
                          messages: MessageStore,
                          users: UserStore,
                          activity: ActivityStore,
                          chat: ChatService,
                          voice: VoiceService,
                          val sessions: SessionAuth,
                          val access: Access2) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "channels" => getChannels(req)
    case req @ GET -> Root / "api" / "channels" / "browse" => getChannelsBrowse(req)
    case req @ POST -> Root / "api" / "channels" => postChannels(req)
    case req @ GET -> Root / "api" / "channels" / "by-name" / name => getChannelsByName(req, name)
    case req @ POST -> Root / "api" / "channels" / "lookup" => postChannelsLookup(req)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) => getChannel(req, id)
    case req @ PATCH -> Root / "api" / "channels" / UuidVar(id) => patchChannel(req, id)
    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) => deleteChannel(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "join" => postChannelJoin(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "leave" => postChannelLeave(req, id)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "members" => getChannelMembers(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "members" => postChannelMembers(req, id)
    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "members" / UuidVar(uid) =>
      deleteChannelMember(req, id, uid)
    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "members" / UuidVar(uid) / "role" =>
      putChannelMemberRole(req, id, uid)
    case req @ PATCH -> Root / "api" / "channels" / UuidVar(id) / "prefs" => patchChannelPrefs(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "read" => postChannelRead(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "unread" => postChannelUnread(req, id)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "mention-groups" => getChannelMentionGroups(req, id)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" => getChannelBookmarks(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" => postChannelBookmarks(req, id)
    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" / UuidVar(bid) =>
      putChannelBookmark(req, id, bid)
    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" / UuidVar(bid) =>
      deleteChannelBookmark(req, id, bid)
    case req @ GET -> Root / "api" / "dms" => getDms(req)
    case req @ POST -> Root / "api" / "dms" => postDms(req)
  }

  private def getChannels(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => channels.listForUser(s.user.id).flatMap(Ok(_)))

  private def getChannelsBrowse(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      channels.browse(
        s.user.id,
        param(req, "q"),
        param(req, "archived").contains("true"),
        intParam(req, "limit", 200, 500)
      ).flatMap(Ok(_))
    )

  private def postChannels(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[CreateChannelReq](req)(r => respond(chat.createChannel(s, r))(Created(_))))

  private def getChannelsByName(req: Request[IO], name: String): IO[Response[IO]] =
    authed(req)(s =>
      channels.byName(name).flatMap {
        case None => err(ErrorCode.ChannelNotFound)
        case Some(c) => respond(access.channel(s, c.id, Need.Read))(_ => channelDto(c.id, s))
      }
    )

  private def postChannelsLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[MessageIdsReq](req)(r =>
        if r.ids.size > 100 then err(ErrorCode.TooManyIds)
        else
          r.ids.distinct.traverse[IO, Either[AppError, ChannelCtx]](id => access.channel(s, id, Need.Read)).flatMap(
            cs =>
              Ok(Json.obj("channels" -> cs.collect {
                case Right(c) if !c.channel.isDm =>
                  Json.obj(
                    "id" -> c.id.asJson,
                    "name" -> c.channel.name.asJson,
                    "kind" -> c.channel.kind.asJson,
                    "topic" -> c.channel.topic.asJson
                  )
              }.asJson))
          )
      )
    )

  private def getChannel(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) => channelDto(id, s))

  private def patchChannel(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[UpdateChannelReq](req)(r =>
        // ボイスチャンネルをやめた・アーカイブしたら、入っている人を外す
        okJson(chat.updateChannel(s, id, r).flatTap(_.traverse_(_ =>
          voice.channelChanged(id).whenA(r.voice.contains(false) || r.archived.contains(true))
        )))
      )
    )

  private def deleteChannel(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req)(s =>
      respond(access.channel(s, id, Need.Read)) { c =>
        channels.delete(id) *> activity.audit(
          Some(s.user.id),
          s.viaString,
          "channel.delete",
          Some(id.toString),
          Json.obj("name" -> c.channel.name.asJson)
        ) *>
          NoContent()
      }
    )

  private def postChannelJoin(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => okJson(chat.join(s, id)))

  private def postChannelLeave(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => respond(chat.removeMember(s, id, s.user.id))(_ => NoContent()))

  private def getChannelMembers(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((_, _) => channels.members(id).flatMap(Ok(_)))

  private def postChannelMembers(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[AddMembersReq](req)(r =>
        respond(chat.addMembers(s, id, r.userIds))(added => Ok(Json.obj("added" -> added.asJson)))
      )
    )

  private def deleteChannelMember(req: Request[IO], id: UUID, uid: UUID): IO[Response[IO]] =
    authed(req)(s => respond(chat.removeMember(s, id, uid))(_ => NoContent()))

  private def putChannelMemberRole(req: Request[IO], id: UUID, uid: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Manage)((s, _) =>
      body[SetRoleReq](req) { r =>
        if !Set(ChannelRole.Admin, ChannelRole.Member).contains(r.role) then
          err(ErrorCode.MemberRoleInvalid)
        else
          channels.adminCount(id).flatMap { n =>
            if r.role == ChannelRole.Member && n <= 1 && uid == s.user.id then
              err(ErrorCode.ChannelLastAdmin)
            else
              channels.setRole(id, uid, r.role).flatMap(ok =>
                if ok then messages.eventIO(id, EventKind.ChannelUpdated, actor = Some(s.user.id)) *> NoContent()
                else err(ErrorCode.NotChannelMember)
              )
          }
      }
    )

  private def patchChannelPrefs(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Member)((s, _) =>
      body[MemberPrefReq](req)(r => channels.setPrefs(id, s.user.id, r) *> channelDto(id, s))
    )

  private def postChannelRead(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Member)((s, _) =>
      body[ReadReq](req) { r =>
        for {
          seq <- if r.seq > 0 then IO.pure(r.seq) else messages.latestSeq(id)
          _ <- channels.markRead(id, s.user.id, seq)
          _ <- activity.markChannelRead(s.user.id, id, seq)
          res <- NoContent()
        } yield res
      }
    )

  private def postChannelUnread(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Member)((s, _) =>
      body[ReadReq](req)(r => channels.markUnread(id, s.user.id, r.seq) *> NoContent())
    )

  private def getChannelMentionGroups(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((_, c) =>
      channels.memberIds(id).flatMap { ms =>
        users.groups.map { gs =>
          val special = if c.channel.isDm then Nil
          else List(MentionGroupDto("r:channel", "channel", ms.size), MentionGroupDto("r:here", "here", ms.size))
          special ++ gs.filter(_._3 > 0).map((gid, n, cnt) => MentionGroupDto(s"g:$gid", n, cnt))
        }.flatMap(Ok(_))
      }
    )

  private def getChannelBookmarks(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((_, _) => channels.bookmarks(id).flatMap(Ok(_)))

  private def postChannelBookmarks(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Post)((s, _) =>
      body[BookmarkReq](req)(r =>
        validBookmark(r) match {
          case Some(e) => err(ErrorCode.BookmarkInvalid, e)
          case None => channels.addBookmark(id, r, s.user.id) *>
              messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *>
              channels.bookmarks(id).flatMap(Created(_))
        }
      )
    )

  private def putChannelBookmark(req: Request[IO], id: UUID, bid: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Post)((s, _) =>
      body[BookmarkReq](req)(r =>
        validBookmark(r) match {
          case Some(e) => err(ErrorCode.BookmarkInvalid, e)
          case None => channels.updateBookmark(id, bid, r) *>
              messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *>
              channels.bookmarks(id).flatMap(Ok(_))
        }
      )
    )

  private def deleteChannelBookmark(req: Request[IO], id: UUID, bid: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Post)((s, _) =>
      channels.deleteBookmark(id, bid) *>
        messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *> NoContent()
    )

  private def getDms(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => {
      val others =
        param(req, "userIds").toList.flatMap(_.split(',').toList).flatMap(uuidOpt).distinct.filterNot(_ == s.user.id)
      channels.findDm(s.user.id :: others).flatMap {
        case Some(c) => channelDto(c.id, s)
        case None => err(ErrorCode.DmNotFound)
      }
    })

  private def postDms(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[OpenDmReq](req)(r => okJson(chat.openDm(s, r.userIds))))

  private def channelDto(id: UUID, s: AuthSession): IO[Response[IO]] =
    channels.get(id, s.user.id).flatMap(_.fold(err(ErrorCode.ChannelNotFound))(Ok(_)))

  private def validBookmark(r: BookmarkReq): Option[String] =
    if r.title.trim.isEmpty then Some("名前を入力してください")
    else if RichText.safeUrl(r.url).isEmpty || r.url.trim.startsWith("#") then Some("URL は http(s):// か / で始めてください")
    else None
}
