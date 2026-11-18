package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{`Content-Disposition`, `Content-Type`}
import org.typelevel.ci.CIString
import stui.core.*
import stui.db.*
import stui.shared.*
import stui.storage.BlobStore

import java.util.UUID

/** チャンネル・DM・メンバー・ブックマーク。 */
final class ChannelRoutes(channels: ChannelStore, messages: MessageStore, users: UserStore, activity: ActivityStore, chat: ChatService,
    val sessions: SessionAuth, val access: Access2) extends RouteSupport:

  private def channelDto(id: UUID, s: AuthSession): IO[Response[IO]] =
    channels.get(id, s.user.id).flatMap(_.fold(err(Status.NotFound, "チャンネルが見つかりません"))(Ok(_)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "channels" => authed(req)(s => channels.listForUser(s.user.id).flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "channels" / "browse" =>
      authed(req)(s => channels.browse(s.user.id, param(req, "q"), param(req, "archived").contains("true"), intParam(req, "limit", 200, 500)).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "channels" => authed(req)(s => body[CreateChannelReq](req)(r => respond(chat.createChannel(s, r))(Created(_))))

    case req @ GET -> Root / "api" / "channels" / "by-name" / name =>
      authed(req)(s => channels.byName(name).flatMap {
        case None => err(Status.NotFound, "チャンネルが見つかりません")
        case Some(c) => respond(access.channel(s, c.id, Need.Read))(_ => channelDto(c.id, s))
      })

    /** 連携サービス向け: チャンネルの名前をまとめて読む {ids}（見られるものだけ）。 */
    case req @ POST -> Root / "api" / "channels" / "lookup" =>
      authed(req)(s => body[MessageIdsReq](req)(r =>
        if r.ids.size > 100 then err(Status.BadRequest, "ids は100件までです")
        else r.ids.distinct.traverse[IO, Either[Fail, ChannelCtx]](id => access.channel(s, id, Need.Read)).flatMap(cs =>
          Ok(Json.obj("channels" -> cs.collect { case Right(c) if !c.channel.isDm =>
            Json.obj("id" -> c.id.asJson, "name" -> c.channel.name.asJson, "kind" -> c.channel.kind.asJson, "topic" -> c.channel.topic.asJson)
          }.asJson)))))

    case req @ GET -> Root / "api" / "channels" / UuidVar(id) => withChannel(req, id, Need.Read)((s, _) => channelDto(id, s))

    case req @ PATCH -> Root / "api" / "channels" / UuidVar(id) =>
      authed(req)(s => body[UpdateChannelReq](req)(r => okJson(chat.updateChannel(s, id, r))))

    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) =>
      admin(req)(s => respond(access.channel(s, id, Need.Read)) { c =>
        channels.delete(id) *> activity.audit(Some(s.user.id), s.viaString, "channel.delete", Some(id.toString), Json.obj("name" -> c.channel.name.asJson)) *>
          NoContent()
      })

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "join" => authed(req)(s => okJson(chat.join(s, id)))

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "leave" => authed(req)(s => respond(chat.removeMember(s, id, s.user.id))(_ => NoContent()))

    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "members" => withChannel(req, id, Need.Read)((_, _) => channels.members(id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "members" =>
      authed(req)(s => body[AddMembersReq](req)(r => respond(chat.addMembers(s, id, r.userIds))(added => Ok(Json.obj("added" -> added.asJson)))))

    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "members" / UuidVar(uid) =>
      authed(req)(s => respond(chat.removeMember(s, id, uid))(_ => NoContent()))

    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "members" / UuidVar(uid) / "role" =>
      withChannel(req, id, Need.Manage)((s, _) => body[SetRoleReq](req) { r =>
        if !Set(ChannelRole.Admin, ChannelRole.Member).contains(r.role) then err(Status.BadRequest, "role は admin か member です")
        else channels.adminCount(id).flatMap { n =>
          if r.role == ChannelRole.Member && n <= 1 && uid == s.user.id then err(Status.BadRequest, "管理者が1人もいなくなります")
          else channels.setRole(id, uid, r.role).flatMap(ok => if ok then messages.eventIO(id, EventKind.ChannelUpdated, actor = Some(s.user.id)) *> NoContent()
            else err(Status.NotFound, "メンバーではありません"))
        }
      })

    case req @ PATCH -> Root / "api" / "channels" / UuidVar(id) / "prefs" =>
      withChannel(req, id, Need.Member)((s, _) => body[MemberPrefReq](req)(r => channels.setPrefs(id, s.user.id, r) *> channelDto(id, s)))

    /** ここまで読んだ（seq まで）。チャンネルの通知も既読にする。 */
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "read" =>
      withChannel(req, id, Need.Member)((s, _) => body[ReadReq](req) { r =>
        for
          seq <- if r.seq > 0 then IO.pure(r.seq) else messages.latestSeq(id)
          _   <- channels.markRead(id, s.user.id, seq)
          _   <- activity.markChannelRead(s.user.id, id, seq)
          res <- NoContent()
        yield res
      })

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "unread" =>
      withChannel(req, id, Need.Member)((s, _) => body[ReadReq](req)(r => channels.markUnread(id, s.user.id, r.seq) *> NoContent()))

    /** まとめてメンションできる相手（チャンネルの全員・IdP のグループ）。 */
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "mention-groups" =>
      withChannel(req, id, Need.Read)((_, c) => channels.memberIds(id).flatMap { ms =>
        users.groups.map { gs =>
          val special = if c.channel.isDm then Nil else List(MentionGroupDto("r:channel", "channel", ms.size), MentionGroupDto("r:here", "here", ms.size))
          special ++ gs.filter(_._3 > 0).map((gid, n, cnt) => MentionGroupDto(s"g:$gid", n, cnt))
        }.flatMap(Ok(_))
      })

    // ---------------------------------------------------------------- ブックマーク（ヘッダーのリンク）

    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" =>
      withChannel(req, id, Need.Read)((_, _) => channels.bookmarks(id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" =>
      withChannel(req, id, Need.Post)((s, _) => body[BookmarkReq](req)(r => validBookmark(r) match
        case Some(e) => err(Status.BadRequest, e)
        case None => channels.addBookmark(id, r, s.user.id) *> messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *>
            channels.bookmarks(id).flatMap(Created(_))))

    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" / UuidVar(bid) =>
      withChannel(req, id, Need.Post)((s, _) => body[BookmarkReq](req)(r => validBookmark(r) match
        case Some(e) => err(Status.BadRequest, e)
        case None => channels.updateBookmark(id, bid, r) *> messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *>
            channels.bookmarks(id).flatMap(Ok(_))))

    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "bookmarks" / UuidVar(bid) =>
      withChannel(req, id, Need.Post)((s, _) => channels.deleteBookmark(id, bid) *>
        messages.eventIO(id, EventKind.BookmarkChanged, actor = Some(s.user.id)) *> NoContent())

    // ---------------------------------------------------------------- DM

    /** 既にある DM を探す（?userIds=a,b。作らないので参照専用のキーでも使える）。 */
    case req @ GET -> Root / "api" / "dms" =>
      authed(req)(s => {
        val others = param(req, "userIds").toList.flatMap(_.split(',').toList).flatMap(uuidOpt).distinct.filterNot(_ == s.user.id)
        channels.findDm(s.user.id :: others).flatMap {
          case Some(c) => channelDto(c.id, s)
          case None    => err(Status.NotFound, "DM はまだありません")
        }
      })

    case req @ POST -> Root / "api" / "dms" => authed(req)(s => body[OpenDmReq](req)(r => okJson(chat.openDm(s, r.userIds))))
  }

  private def validBookmark(r: BookmarkReq): Option[String] =
    if r.title.trim.isEmpty then Some("名前を入力してください")
    else if RichText.safeUrl(r.url).isEmpty || r.url.trim.startsWith("#") then Some("URL は http(s):// か / で始めてください")
    else None

/** メッセージ・スレッド・リアクション・ピン留め・保存・下書き・添付ファイル・画面の同期。 */
final class MessageRoutes(messages: MessageStore, channels: ChannelStore, activity: ActivityStore, chat: ChatService, blobs: Option[BlobStore],
    val sessions: SessionAuth, val access: Access2, cfg: ApiConfig) extends RouteSupport:

  private val inlineTypes = Set("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain", "video/mp4",
    "video/webm", "audio/mpeg", "audio/ogg", "audio/wav")

  private def rootParam(req: Request[IO]): Option[UUID] = param(req, "rootId").flatMap(uuidOpt)

  /** 見られるメッセージだけに絞る。 */
  private def visible(s: AuthSession, ms: List[MessageDto]): IO[List[MessageDto]] =
    ms.map(_.channelId).distinct.traverse(c => access.channel(s, c, Need.Read).map(r => c -> r.isRight)).map(_.toMap).map(ok =>
      ms.filter(m => ok.getOrElse(m.channelId, false)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "messages" =>
      withChannel(req, id, Need.Read)((s, _) =>
        messages.page(id, s.user.id, param(req, "before").flatMap(_.toLongOption), param(req, "after").flatMap(_.toLongOption),
          param(req, "around").flatMap(_.toLongOption), intParam(req, "limit", 50, 200)).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "messages" =>
      authed(req)(s => body[PostMessageReq](req)(r => respond(chat.post(s, id, r))(Created(_))))

    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "pins" =>
      withChannel(req, id, Need.Read)((s, _) => messages.pinned(id, s.user.id).flatMap(Ok(_)))

    // ---------------------------------------------------------------- 下書き
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "draft" =>
      withChannel(req, id, Need.Read)((s, _) => messages.draft(s.user.id, id, rootParam(req)).flatMap(d => Ok(d.asJson)))

    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "draft" =>
      withChannel(req, id, Need.Read)((s, _) => body[Json](req)(j =>
        chat.parseBody(j.hcursor.downField("body").focus.getOrElse(Json.Null)) match
          case Left(f) => fail(f)
          case Right(doc) => messages.saveDraft(s.user.id, id, rootParam(req), doc) *> NoContent()))

    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "draft" =>
      withChannel(req, id, Need.Read)((s, _) => messages.deleteDraft(s.user.id, id, rootParam(req)) *> NoContent())

    case req @ GET -> Root / "api" / "drafts" => authed(req)(s => messages.drafts(s.user.id).flatMap(Ok(_)))

    // ---------------------------------------------------------------- 添付ファイル
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "files" =>
      withChannel(req, id, Need.Post)((s, _) => blobs match
        case None => err(Status.ServiceUnavailable, "添付ファイルの保存先（S3）が設定されていません")
        case Some(store) =>
          withUpload(req, cfg.maxUploadBytes) { up =>
            val key = s"channels/$id/${UUID.randomUUID()}"
            for
              sha <- BlobStore.sha256Hex(up.path)
              _   <- store.put(key, up.path, up.contentType)
              a   <- messages.addAttachment(id, key, up.name, up.size, up.contentType, sha, s.user.id)
              r   <- Created(AttachmentDto(a.id, a.fileName, a.size, a.contentType, Files.url(a.id, a.fileName)))
            yield r
          })

    case req @ GET -> Root / "api" / "files" / UuidVar(fid) / _ => download(req, fid)
    case req @ GET -> Root / "api" / "files" / UuidVar(fid) => download(req, fid)

    // ---------------------------------------------------------------- メッセージ
    case req @ GET -> Root / "api" / "messages" / UuidVar(id) =>
      withMessage(req, id, Need.Read)((s, _, _) => messages.view(id, s.user.id).flatMap(v => Ok(v.get)))

    case req @ PUT -> Root / "api" / "messages" / UuidVar(id) =>
      authed(req)(s => body[EditMessageReq](req)(r => okJson(chat.edit(s, id, r.body))))

    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) => authed(req)(s => respond(chat.delete(s, id))(_ => NoContent()))

    case req @ GET -> Root / "api" / "messages" / UuidVar(id) / "thread" =>
      withMessage(req, id, Need.Read)((s, c, m) => messages.thread(m.rootId.getOrElse(id), s.user.id).flatMap {
        case None => err(Status.NotFound, "メッセージが見つかりません")
        case Some((root, replies, following)) => Ok(ThreadDto(root, replies, following, c.channel.brief))
      })

    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "replies" =>
      authed(req)(s => body[PostMessageReq](req)(r => messages.find(id).flatMap {
        case None => err(Status.NotFound, "メッセージが見つかりません")
        case Some(m) => respond(chat.post(s, m.channelId, r.copy(rootId = Some(id))))(Created(_))
      }))

    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "reactions" =>
      authed(req)(s => body[ReactionReq](req)(r => okJson(chat.react(s, id, r.emoji, on = true))))

    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "reactions" =>
      authed(req)(s => param(req, "emoji").fold(err(Status.BadRequest, "emoji を指定してください"))(e => okJson(chat.react(s, id, e, on = false))))

    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "pin" => authed(req)(s => respond(chat.pin(s, id, on = true))(_ => NoContent()))
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "pin" => authed(req)(s => respond(chat.pin(s, id, on = false))(_ => NoContent()))

    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "save" =>
      withMessage(req, id, Need.Read)((s, _, _) => messages.save(id, s.user.id, on = true) *> NoContent())
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "save" =>
      authed(req)(s => messages.save(id, s.user.id, on = false) *> NoContent())

    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "follow" =>
      withMessage(req, id, Need.Read)((s, _, m) => messages.setFollow(m.rootId.getOrElse(id), s.user.id, on = true) *> NoContent())
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "follow" =>
      withMessage(req, id, Need.Read)((s, _, m) => messages.setFollow(m.rootId.getOrElse(id), s.user.id, on = false) *> NoContent())

    /** スレッドを読んだ（フォローの未読と、そのスレッドの通知を既読にする）。 */
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "thread-read" =>
      withMessage(req, id, Need.Read)((s, _, m) => {
        val root = m.rootId.getOrElse(id)
        messages.markThreadRead(root, s.user.id) *> activity.markThreadRead(s.user.id, root) *> NoContent()
      })

    /** 何件かのメッセージをまとめて読む（画面の同期で変わったものを取り直す）。 */
    case req @ POST -> Root / "api" / "messages" / "batch" =>
      authed(req)(s => body[MessageIdsReq](req)(r =>
        if r.ids.size > 200 then err(Status.BadRequest, "ids は200件までです")
        else messages.viewsByIds(r.ids.distinct, s.user.id).flatMap(visible(s, _)).flatMap(Ok(_))))

    /** 連携サービス向け: メッセージの概要 {ids}（見られるものだけ）。 */
    case req @ POST -> Root / "api" / "messages" / "lookup" =>
      authed(req)(s => body[MessageIdsReq](req)(r =>
        if r.ids.size > 100 then err(Status.BadRequest, "ids は100件までです")
        else messages.lookup(r.ids.distinct, s.user.id).flatMap(rs => Ok(Json.obj("messages" -> rs.map((id, c, author, text) =>
          Json.obj("id" -> id.asJson, "channel" -> c.asJson, "author" -> author.asJson, "text" -> text.asJson,
            "url" -> s"${cfg.publicUrl}/m/$id".asJson)).asJson)))))

    case req @ GET -> Root / "api" / "threads" =>
      authed(req)(s => messages.followedThreads(s.user.id, intParam(req, "limit", 50, 200)).flatMap(ts =>
        Ok(ts.map((root, c, unread, last) => ThreadSummaryDto(root, c, unread, last)))))

    case req @ GET -> Root / "api" / "threads" / "unread" =>
      authed(req)(s => messages.unreadThreads(s.user.id).flatMap(n => Ok(Json.obj("count" -> n.asJson))))

    case req @ GET -> Root / "api" / "saved" / "count" =>
      authed(req)(s => messages.savedCount(s.user.id).flatMap(n => Ok(Json.obj("count" -> n.asJson))))

    case req @ GET -> Root / "api" / "saved" =>
      authed(req)(s => messages.saved(s.user.id, intParam(req, "limit", 100, 500)).flatMap(xs => Ok(xs.map(MessageInChannelDto.apply))))

    /** 画面の同期: since より新しい変更（watch は参加していない公開チャンネルを見ているとき）。 */
    case req @ GET -> Root / "api" / "changes" =>
      authed(req)(s => messages.changes(s.user.id, param(req, "since").flatMap(_.toLongOption).getOrElse(0L), param(req, "watch").flatMap(uuidOpt))
        .flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "changes" / "latest" =>
      authed(req)(_ => messages.latestEvent.flatMap(id => Ok(Json.obj("latest" -> id.asJson))))
  }

  private def download(req: Request[IO], fid: UUID): IO[Response[IO]] =
    authed(req)(s => messages.attachment(fid).flatMap {
      case None => err(Status.NotFound, "ファイルが見つかりません")
      case Some(a) =>
        respond(access.channel(s, a.channelId, Need.Read))(_ =>
          if a.messageId.isEmpty && !a.createdBy.contains(s.user.id) then err(Status.NotFound, "ファイルが見つかりません")
          else blobs.fold(err(Status.ServiceUnavailable, "保存先が設定されていません"))(store =>
            store.get(a.s3Key).flatMap {
              case None => err(Status.NotFound, "ファイルの中身が見つかりません")
              case Some((meta, bytes)) =>
                val inline = inlineTypes.contains(a.contentType) && !param(req, "download").contains("1")
                val mt = if inline then MediaType.parse(a.contentType).getOrElse(MediaType.application.`octet-stream`) else MediaType.application.`octet-stream`
                val hs = List[Header.ToRaw](
                  `Content-Type`(mt),
                  `Content-Disposition`(if inline then "inline" else "attachment", Map(CIString("filename*") -> a.fileName)),
                  "X-Content-Type-Options" -> "nosniff",
                  "Cache-Control" -> "private, max-age=3600"
                ) ++ (if a.contentType == "application/pdf" then Nil else List[Header.ToRaw]("Content-Security-Policy" -> "sandbox"))
                IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size))).putHeaders(hs*))
            }))
    })
