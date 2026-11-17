package stui.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しのエラー. */
final case class ApiError(status: Int, message: String) extends Exception(message)

/** バックエンドの REST API. 401 はログイン画面へ、エラーは {"error": ...} の文言にする. */
object Api {

  private def request(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Option[Json]] = {
    val init = new dom.RequestInit {}
    init.method = method
    init.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      init.headers = js.Dictionary("Content-Type" -> "application/json")
      init.body = b.noSpaces
    }
    dom.fetch(url, init).toFuture.flatMap { res =>
      if (res.status == 401) {
        val back = dom.window.location.pathname + dom.window.location.search
        if (!dom.window.location.pathname.startsWith("/login"))
          dom.window.location.href = "/auth/login?returnTo=" + js.URIUtils.encodeURIComponent(back)
        Future.failed(ApiError(401, "ログインしてください"))
      } else if (!res.ok) {
        res.text().toFuture.recover(_ => "").flatMap { t =>
          val msg = parse(t).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(s"${res.status} ${res.statusText}")
          Future.failed(ApiError(res.status, msg))
        }
      } else if (res.status == 204) Future.successful(None)
      else res.text().toFuture.map(t => if (t.isEmpty) None else Some(parse(t).fold(throw _, identity)))
    }
  }

  private def as[A: Decoder](j: Option[Json]): A = j.getOrElse(Json.Null).as[A].fold(e => throw ApiError(0, e.getMessage), identity)

  def get[A: Decoder](url: String): Future[A] = request(dom.HttpMethod.GET, url).map(as[A])
  def post[A: Decoder](url: String, body: Json = Json.obj()): Future[A] = request(dom.HttpMethod.POST, url, Some(body)).map(as[A])
  def put[A: Decoder](url: String, body: Json): Future[A] = request(dom.HttpMethod.PUT, url, Some(body)).map(as[A])
  def patch[A: Decoder](url: String, body: Json): Future[A] = request(dom.HttpMethod.PATCH, url, Some(body)).map(as[A])
  def send(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Unit] = request(method, url, body).map(_ => ())
  def delete(url: String): Future[Unit] = send(dom.HttpMethod.DELETE, url)
  def postUnit(url: String, body: Json = Json.obj()): Future[Unit] = send(dom.HttpMethod.POST, url, Some(body))
  def putUnit(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PUT, url, Some(body))
  def patchUnit(url: String, body: Json): Future[Unit] = send(dom.HttpMethod.PATCH, url, Some(body))

  def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  def query(params: (String, Option[String])*): String = {
    val q = params.collect { case (k, Some(v)) if v.nonEmpty => s"$k=${enc(v)}" }
    if (q.isEmpty) "" else q.mkString("?", "&", "")
  }

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(name: String, email: String, admin: Boolean, returnTo: String): Future[Json] =
    post[Json]("/auth/dev-login", Json.obj("name" -> name.asJson, "email" -> email.asJson, "admin" -> admin.asJson, "returnTo" -> returnTo.asJson))
  def logout(): Future[Unit] = postUnit("/auth/logout")
  def users(q: String, limit: Int = 20): Future[List[UserBrief]] = get[List[UserBrief]]("/api/users" + query("q" -> Some(q), "limit" -> Some(limit.toString)))

  // ---------------------------------------------------------------- チャンネル

  def channels(): Future[List[ChannelDto]] = get[List[ChannelDto]]("/api/channels")
  def channel(id: UUID): Future[ChannelDto] = get[ChannelDto](s"/api/channels/$id")
  def browse(q: String, archived: Boolean = false): Future[List[ChannelDto]] =
    get[List[ChannelDto]]("/api/channels/browse" + query("q" -> Some(q), "archived" -> Option.when(archived)("true")))
  def createChannel(r: CreateChannelReq): Future[ChannelDto] = post[ChannelDto]("/api/channels", r.asJson)
  def updateChannel(id: UUID, r: UpdateChannelReq): Future[ChannelDto] = patch[ChannelDto](s"/api/channels/$id", r.asJson.dropNullValues)
  def join(id: UUID): Future[ChannelDto] = post[ChannelDto](s"/api/channels/$id/join")
  def leave(id: UUID): Future[Unit] = postUnit(s"/api/channels/$id/leave")
  def members(id: UUID): Future[List[ChannelMemberDto]] = get[List[ChannelMemberDto]](s"/api/channels/$id/members")
  def addMembers(id: UUID, users: List[UUID]): Future[Unit] = postUnit(s"/api/channels/$id/members", AddMembersReq(users).asJson)
  def prefs(id: UUID, r: MemberPrefReq): Future[ChannelDto] = patch[ChannelDto](s"/api/channels/$id/prefs", r.asJson.dropNullValues)
  def markRead(id: UUID, seq: Long): Future[Unit] = postUnit(s"/api/channels/$id/read", ReadReq(seq).asJson)
  def markUnread(id: UUID, seq: Long): Future[Unit] = postUnit(s"/api/channels/$id/unread", ReadReq(seq).asJson)
  def openDm(users: List[UUID]): Future[ChannelDto] = post[ChannelDto]("/api/dms", OpenDmReq(users).asJson)
  def bookmarks(id: UUID): Future[List[BookmarkDto]] = get[List[BookmarkDto]](s"/api/channels/$id/bookmarks")
  def pins(id: UUID): Future[List[MessageDto]] = get[List[MessageDto]](s"/api/channels/$id/pins")
  def mentionGroups(id: UUID): Future[List[MentionGroupDto]] = get[List[MentionGroupDto]](s"/api/channels/$id/mention-groups")

  // ---------------------------------------------------------------- メッセージ

  def messages(id: UUID, before: Option[Long] = None, after: Option[Long] = None, around: Option[Long] = None, limit: Int = 50): Future[MessagePageDto] =
    get[MessagePageDto](s"/api/channels/$id/messages" + query("before" -> before.map(_.toString), "after" -> after.map(_.toString),
      "around" -> around.map(_.toString), "limit" -> Some(limit.toString)))
  def postMessage(id: UUID, r: PostMessageReq): Future[MessageDto] = post[MessageDto](s"/api/channels/$id/messages", r.asJson.dropNullValues)
  def message(id: UUID): Future[MessageDto] = get[MessageDto](s"/api/messages/$id")
  def editMessage(id: UUID, body: Json): Future[MessageDto] = put[MessageDto](s"/api/messages/$id", EditMessageReq(body).asJson)
  def deleteMessage(id: UUID): Future[Unit] = delete(s"/api/messages/$id")
  def thread(id: UUID): Future[ThreadDto] = get[ThreadDto](s"/api/messages/$id/thread")
  def batch(ids: List[UUID]): Future[List[MessageDto]] = post[List[MessageDto]]("/api/messages/batch", MessageIdsReq(ids).asJson)
  def react(id: UUID, emoji: String, on: Boolean): Future[List[ReactionDto]] =
    if (on) post[List[ReactionDto]](s"/api/messages/$id/reactions", ReactionReq(emoji).asJson)
    else request(dom.HttpMethod.DELETE, s"/api/messages/$id/reactions?emoji=${enc(emoji)}").map(as[List[ReactionDto]])
  def pin(id: UUID, on: Boolean): Future[Unit] = send(if (on) dom.HttpMethod.POST else dom.HttpMethod.DELETE, s"/api/messages/$id/pin")
  def save(id: UUID, on: Boolean): Future[Unit] = send(if (on) dom.HttpMethod.POST else dom.HttpMethod.DELETE, s"/api/messages/$id/save")
  def follow(id: UUID, on: Boolean): Future[Unit] = send(if (on) dom.HttpMethod.POST else dom.HttpMethod.DELETE, s"/api/messages/$id/follow")
  def threadRead(id: UUID): Future[Unit] = postUnit(s"/api/messages/$id/thread-read")
  def draft(id: UUID, root: Option[UUID]): Future[Option[DraftDto]] = get[Option[DraftDto]](s"/api/channels/$id/draft" + query("rootId" -> root.map(_.toString)))
  def saveDraft(id: UUID, root: Option[UUID], body: Json): Future[Unit] =
    putUnit(s"/api/channels/$id/draft" + query("rootId" -> root.map(_.toString)), Json.obj("body" -> body))
  def deleteDraft(id: UUID, root: Option[UUID]): Future[Unit] = delete(s"/api/channels/$id/draft" + query("rootId" -> root.map(_.toString)))
  def changes(since: Long, watch: Option[UUID]): Future[ChangesDto] =
    get[ChangesDto]("/api/changes" + query("since" -> Some(since.toString), "watch" -> watch.map(_.toString)))
  def latestEvent(): Future[Long] = get[Json]("/api/changes/latest").map(_.hcursor.get[Long]("latest").getOrElse(0L))
  def meeting(id: UUID, name: Option[String], root: Option[UUID]): Future[MessageDto] =
    post[MessageDto](s"/api/channels/$id/meeting", MeetingReq(name, root).asJson.dropNullValues)

  /** ファイルを multipart/form-data（file）で送る. */
  def sendFile[A: Decoder](url: String, file: dom.File): Future[A] = {
    val fd = new dom.FormData()
    fd.append("file", file, file.name)
    val init = new dom.RequestInit {}
    init.method = dom.HttpMethod.POST
    init.credentials = dom.RequestCredentials.`same-origin`
    init.body = fd
    dom.fetch(url, init).toFuture.flatMap { res =>
      res.text().toFuture.map { t =>
        val j = parse(t).getOrElse(Json.Null)
        if (res.ok) j.as[A].fold(e => throw ApiError(0, e.getMessage), identity)
        else throw ApiError(res.status, j.hcursor.get[String]("error").getOrElse(res.statusText))
      }
    }
  }

  /** 添付ファイルのアップロード（投稿の前に送り、投稿の attachmentIds に入れる）. */
  def upload(channelId: UUID, file: dom.File): Future[AttachmentDto] = sendFile[AttachmentDto](s"/api/channels/$channelId/files", file)

  // ---------------------------------------------------------------- 検索・通知・スレッド・保存・リマインダー

  def search(q: String, limit: Int = 50, offset: Int = 0): Future[SearchResultDto] =
    get[SearchResultDto]("/api/search" + query("q" -> Some(q), "limit" -> Some(limit.toString), "offset" -> Some(offset.toString)))
  def notifications(kinds: List[String], unread: Boolean): Future[NotificationListDto] =
    get[NotificationListDto]("/api/notifications" + query("kinds" -> Some(kinds.mkString(",")), "unread" -> Option.when(unread)("true"), "limit" -> Some("100")))
  def unread(): Future[UnreadDto] = get[UnreadDto]("/api/notifications/unread")
  def threads(): Future[List[ThreadSummaryDto]] = get[List[ThreadSummaryDto]]("/api/threads")
  def unreadThreads(): Future[Int] = get[Json]("/api/threads/unread").map(_.hcursor.get[Int]("count").getOrElse(0))
  def savedCount(): Future[Int] = get[Json]("/api/saved/count").map(_.hcursor.get[Int]("count").getOrElse(0))
  def saved(): Future[List[MessageInChannelDto]] = get[List[MessageInChannelDto]]("/api/saved")
  def drafts(): Future[List[DraftListItemDto]] = get[List[DraftListItemDto]]("/api/drafts")
  def reminders(done: Boolean): Future[List[ReminderDto]] = get[List[ReminderDto]]("/api/reminders" + (if (done) "?done=true" else ""))
  def remind(r: ReminderReq): Future[List[ReminderDto]] = post[List[ReminderDto]]("/api/reminders", r.asJson.dropNullValues)
  def scheduled(channelId: Option[UUID]): Future[List[ScheduledDto]] = get[List[ScheduledDto]]("/api/scheduled" + query("channelId" -> channelId.map(_.toString)))

}
