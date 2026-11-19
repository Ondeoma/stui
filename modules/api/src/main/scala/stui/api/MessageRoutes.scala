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
import sbirds.storage.BlobStore

import java.util.UUID

/** メッセージ・スレッド・リアクション・ピン留め・保存・下書き・添付ファイル・画面の同期。 */
final class MessageRoutes(messages: MessageStore,
                          channels: ChannelStore,
                          activity: ActivityStore,
                          chat: ChatService,
                          blobs: Option[BlobStore],
                          val sessions: SessionAuth,
                          val access: Access2,
                          cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "messages" => getChannelMessages(req, id)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "messages" => postChannelMessages(req, id)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "pins" => getChannelPins(req, id)
    case req @ GET -> Root / "api" / "channels" / UuidVar(id) / "draft" => getChannelDraft(req, id)
    case req @ PUT -> Root / "api" / "channels" / UuidVar(id) / "draft" => putChannelDraft(req, id)
    case req @ DELETE -> Root / "api" / "channels" / UuidVar(id) / "draft" => deleteChannelDraft(req, id)
    case req @ GET -> Root / "api" / "drafts" => getDrafts(req)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "files" => postChannelFiles(req, id)
    case req @ GET -> Root / "api" / "files" / UuidVar(fid) / _ => getFileNamed(req, fid)
    case req @ GET -> Root / "api" / "files" / UuidVar(fid) => getFile(req, fid)
    case req @ GET -> Root / "api" / "messages" / UuidVar(id) => getMessage(req, id)
    case req @ PUT -> Root / "api" / "messages" / UuidVar(id) => putMessage(req, id)
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) => deleteMessage(req, id)
    case req @ GET -> Root / "api" / "messages" / UuidVar(id) / "thread" => getMessageThread(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "replies" => postMessageReplies(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "reactions" => postMessageReactions(req, id)
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "reactions" => deleteMessageReactions(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "pin" => postMessagePin(req, id)
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "pin" => deleteMessagePin(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "save" => postMessageSave(req, id)
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "save" => deleteMessageSave(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "follow" => postMessageFollow(req, id)
    case req @ DELETE -> Root / "api" / "messages" / UuidVar(id) / "follow" => deleteMessageFollow(req, id)
    case req @ POST -> Root / "api" / "messages" / UuidVar(id) / "thread-read" => postMessageThreadRead(req, id)
    case req @ POST -> Root / "api" / "messages" / "batch" => postMessagesBatch(req)
    case req @ POST -> Root / "api" / "messages" / "lookup" => postMessagesLookup(req)
    case req @ GET -> Root / "api" / "threads" => getThreads(req)
    case req @ GET -> Root / "api" / "threads" / "unread" => getThreadsUnread(req)
    case req @ GET -> Root / "api" / "saved" / "count" => getSavedCount(req)
    case req @ GET -> Root / "api" / "saved" => getSaved(req)
    case req @ GET -> Root / "api" / "changes" => getChanges(req)
    case req @ GET -> Root / "api" / "changes" / "latest" => getChangesLatest(req)
  }

  private def getChannelMessages(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) =>
      messages.page(
        id,
        s.user.id,
        param(req, "before").flatMap(_.toLongOption),
        param(req, "after").flatMap(_.toLongOption),
        param(req, "around").flatMap(_.toLongOption),
        intParam(req, "limit", 50, 200)
      ).flatMap(Ok(_))
    )

  private def postChannelMessages(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => body[PostMessageReq](req)(r => respond(chat.post(s, id, r))(Created(_))))

  private def getChannelPins(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) => messages.pinned(id, s.user.id).flatMap(Ok(_)))

  private def getChannelDraft(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) => messages.draft(s.user.id, id, rootParam(req)).flatMap(d => Ok(d.asJson)))

  private def putChannelDraft(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) =>
      body[Json](req)(j =>
        chat.parseBody(j.hcursor.downField("body").focus.getOrElse(Json.Null)) match {
          case Left(f) => fail(f)
          case Right(doc) => messages.saveDraft(s.user.id, id, rootParam(req), doc) *> NoContent()
        }
      )
    )

  private def deleteChannelDraft(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Read)((s, _) => messages.deleteDraft(s.user.id, id, rootParam(req)) *> NoContent())

  private def getDrafts(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => messages.drafts(s.user.id).flatMap(Ok(_)))

  private def postChannelFiles(req: Request[IO], id: UUID): IO[Response[IO]] =
    withChannel(req, id, Need.Post)((s, _) =>
      blobs match {
        case None => err(ErrorCode.StorageNotConfigured)
        case Some(store) =>
          withUpload(req, cfg.maxUploadBytes) { up =>
            val key = s"channels/$id/${UUID.randomUUID()}"
            for {
              sha <- BlobHash.sha256Hex(up.path)
              _ <- store.put(key, up.path, up.contentType)
              a <- messages.addAttachment(id, key, up.name, up.size, up.contentType, sha, s.user.id)
              r <- Created(AttachmentDto(a.id, a.fileName, a.size, a.contentType, Files.url(a.id, a.fileName)))
            } yield r
          }
      }
    )

  private def getFileNamed(req: Request[IO], fid: UUID): IO[Response[IO]] =
    download(req, fid)

  private def getFile(req: Request[IO], fid: UUID): IO[Response[IO]] =
    download(req, fid)

  private def getMessage(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, _, _) => messages.view(id, s.user.id).flatMap(v => Ok(v.get)))

  private def putMessage(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => body[EditMessageReq](req)(r => okJson(chat.edit(s, id, r.body))))

  private def deleteMessage(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => respond(chat.delete(s, id))(_ => NoContent()))

  private def getMessageThread(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, c, m) =>
      messages.thread(m.rootId.getOrElse(id), s.user.id).flatMap {
        case None => err(ErrorCode.MessageNotFound)
        case Some((root, replies, following)) => Ok(ThreadDto(root, replies, following, c.channel.brief))
      }
    )

  private def postMessageReplies(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      body[PostMessageReq](req)(r =>
        messages.find(id).flatMap {
          case None => err(ErrorCode.MessageNotFound)
          case Some(m) => respond(chat.post(s, m.channelId, r.copy(rootId = Some(id))))(Created(_))
        }
      )
    )

  private def postMessageReactions(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => body[ReactionReq](req)(r => okJson(chat.react(s, id, r.emoji, on = true))))

  private def deleteMessageReactions(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      param(req, "emoji").fold(err(ErrorCode.ReactionEmojiRequired))(e =>
        okJson(chat.react(s, id, e, on = false))
      )
    )

  private def postMessagePin(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => respond(chat.pin(s, id, on = true))(_ => NoContent()))

  private def deleteMessagePin(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => respond(chat.pin(s, id, on = false))(_ => NoContent()))

  private def postMessageSave(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, _, _) => messages.save(id, s.user.id, on = true) *> NoContent())

  private def deleteMessageSave(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s => messages.save(id, s.user.id, on = false) *> NoContent())

  private def postMessageFollow(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, _, m) =>
      messages.setFollow(m.rootId.getOrElse(id), s.user.id, on = true) *> NoContent()
    )

  private def deleteMessageFollow(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, _, m) =>
      messages.setFollow(m.rootId.getOrElse(id), s.user.id, on = false) *> NoContent()
    )

  private def postMessageThreadRead(req: Request[IO], id: UUID): IO[Response[IO]] =
    withMessage(req, id, Need.Read)((s, _, m) => {
      val root = m.rootId.getOrElse(id)
      messages.markThreadRead(root, s.user.id) *> activity.markThreadRead(s.user.id, root) *> NoContent()
    })

  private def postMessagesBatch(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[MessageIdsReq](req)(r =>
        if r.ids.size > 200 then err(ErrorCode.TooManyIds, "ids は200件までです")
        else messages.viewsByIds(r.ids.distinct, s.user.id).flatMap(visible(s, _)).flatMap(Ok(_))
      )
    )

  private def postMessagesLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[MessageIdsReq](req)(r =>
        if r.ids.size > 100 then err(ErrorCode.TooManyIds)
        else
          messages.lookup(r.ids.distinct, s.user.id).flatMap(rs =>
            Ok(Json.obj("messages" -> rs.map((id, c, author, text) =>
              Json.obj(
                "id" -> id.asJson,
                "channel" -> c.asJson,
                "author" -> author.asJson,
                "text" -> text.asJson,
                "url" -> s"${cfg.publicUrl}/m/$id".asJson
              )
            ).asJson))
          )
      )
    )

  private def getThreads(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      messages.followedThreads(s.user.id, intParam(req, "limit", 50, 200)).flatMap(ts =>
        Ok(ts.map((root, c, unread, last) => ThreadSummaryDto(root, c, unread, last)))
      )
    )

  private def getThreadsUnread(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => messages.unreadThreads(s.user.id).flatMap(n => Ok(Json.obj("count" -> n.asJson))))

  private def getSavedCount(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => messages.savedCount(s.user.id).flatMap(n => Ok(Json.obj("count" -> n.asJson))))

  private def getSaved(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      messages.saved(s.user.id, intParam(req, "limit", 100, 500)).flatMap(xs => Ok(xs.map(MessageInChannelDto.apply)))
    )

  private def getChanges(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      messages.changes(
        s.user.id,
        param(req, "since").flatMap(_.toLongOption).getOrElse(0L),
        param(req, "watch").flatMap(uuidOpt)
      )
        .flatMap(Ok(_))
    )

  private def getChangesLatest(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ => messages.latestEvent.flatMap(id => Ok(Json.obj("latest" -> id.asJson))))

  private val inlineTypes = Set(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "application/pdf",
    "text/plain",
    "video/mp4",
    "video/webm",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav"
  )

  private def rootParam(req: Request[IO]): Option[UUID] = param(req, "rootId").flatMap(uuidOpt)

  /** 見られるメッセージだけに絞る。 */
  private def visible(s: AuthSession, ms: List[MessageDto]): IO[List[MessageDto]] =
    ms.map(
      _.channelId
    ).distinct.traverse(c => access.channel(s, c, Need.Read).map(r => c -> r.isRight)).map(_.toMap).map(ok =>
      ms.filter(m => ok.getOrElse(m.channelId, false))
    )

  private def download(req: Request[IO], fid: UUID): IO[Response[IO]] =
    authed(req)(s =>
      messages.attachment(fid).flatMap {
        case None => err(ErrorCode.FileNotFound)
        case Some(a) =>
          respond(access.channel(s, a.channelId, Need.Read))(_ =>
            if a.messageId.isEmpty && !a.createdBy.contains(s.user.id) then err(ErrorCode.FileNotFound)
            else
              blobs.fold(err(ErrorCode.StorageNotConfigured, "保存先が設定されていません"))(store =>
                store.get(a.s3Key).flatMap {
                  case None => err(ErrorCode.FileContentNotFound)
                  case Some((meta, bytes)) =>
                    val inline = inlineTypes.contains(a.contentType) && !param(req, "download").contains("1")
                    val mt = if inline then
                      MediaType.parse(a.contentType).getOrElse(MediaType.application.`octet-stream`)
                    else MediaType.application.`octet-stream`
                    val hs = List[Header.ToRaw](
                      `Content-Type`(mt),
                      `Content-Disposition`(
                        if inline then "inline" else "attachment",
                        Map(CIString("filename*") -> a.fileName)
                      ),
                      "X-Content-Type-Options" -> "nosniff",
                      "Cache-Control" -> "private, max-age=3600"
                    ) ++
                      (if a.contentType == "application/pdf" then Nil
                       else List[Header.ToRaw]("Content-Security-Policy" -> "sandbox"))
                    IO.pure(Response[IO](Status.Ok).withEntity(Entity.stream(bytes, Some(meta.size))).putHeaders(hs*))
                }
              )
          )
      }
    )
}
