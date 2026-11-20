package stui.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * 画面全体で共有する状態と、変更の自動同期.
 * サーバーから SSE で知らせを受けると /api/changes で変更を取り、events に流す（各画面が自分に関係のあるものを取り直す）.
 * SSE がつながらないときは10秒ごとに確かめる.
 */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)
  val channels: RV[List[ChannelDto]] = RV(Nil)
  val unread: RV[UnreadDto] = RV(UnreadDto(0, 0))
  val threadsUnread: RV[Int] = RV(0)
  val savedCount: RV[Int] = RV(0)

  /** サイドバーを開いているか（スマホの幅）. */
  val sideOpen: RV[Boolean] = RV(false)

  /** 取得した変更の束（番号, 変更）. 各画面はこれを監視する. */
  val events: RV[(Int, List[ChatEventDto])] = RV((0, Nil))

  /** 開いているダイアログ（チャンネルの作成・DM の作成）. */
  val dialog: RV[String] = RV("")

  private var since: Long = 0L
  private var eventSeq = 0

  /** 表示中のチャンネル（参加していない公開チャンネルの変更も受け取るため）. */
  private var viewing: Option[UUID] = None
  private var memberCache: Map[UUID, List[UserBrief]] = Map.empty
  private var groupCache: Map[UUID, List[MentionGroupDto]] = Map.empty

  def myId: Option[UUID] = me.v.map(_.id)

  /** システム管理者か. */
  def isAdmin = me.mapC(_.exists(_.isAdmin))

  def channel(id: UUID): Option[ChannelDto] = channels.v.find(_.id == id)

  /** チャンネルの表示名（DM は相手の名前）. */
  def title(c: ChannelDto): String =
    if (ChannelKind.isDm(c.kind)) {
      val names = c.dmUsers.map(_.name)
      if (c.dmUsers.map(_.id) == myId.toList) s"${names.mkString}（自分）" else names.mkString("、")
    } else c.name

  def titleOf(b: ChannelBrief): String =
    channel(b.id).map(title).getOrElse(if (ChannelKind.isDm(b.kind)) "DM" else b.name)

  def prefix(kind: String): String =
    if (ChannelKind.isDm(kind)) "" else if (kind == ChannelKind.Private) "🔒 " else "# "

  // ---------------------------------------------------------------- 読み込み

  private var reloadTimer: Option[Int] = None

  /** チャンネルの一覧を読み直す（続けて呼ばれてもまとめて1回）. */
  def reloadChannels(): Unit = if (reloadTimer.isEmpty)
    reloadTimer = Some(dom.window.setTimeout(
      () => {
        reloadTimer = None
        Api.channels().foreach { cs => channels := cs; updateTitle() }
        Api.unreadThreads().foreach(n => if (n != threadsUnread.v) threadsUnread := n)
        refreshSaved()
      },
      200
    ))

  /** 保存済みの件数を読み直す（サイドバーに出す）. */
  def refreshSaved(): Unit = Api.savedCount().foreach(n => if (n != savedCount.v) savedCount := n)

  def loadChannels(): Future[List[ChannelDto]] = {
    refreshSaved(); Api.channels().map { cs => channels := cs; updateTitle(); cs }
  }

  /** 一覧の中のチャンネルを差し替える（設定を変えたとき）. */
  def replace(c: ChannelDto): Unit =
    channels :=
      (if (channels.v.exists(_.id == c.id)) channels.v.map(x => if (x.id == c.id) c else x)
       else if (c.member) channels.v :+ c else channels.v)

  /** 既読にした（一覧の未読の数を消す）. */
  def markedRead(id: UUID, seq: Long): Unit = {
    channels := channels.v.map(c =>
      if (c.id == id) c.copy(unread = 0, mentions = 0, lastReadSeq = math.max(c.lastReadSeq, seq)) else c
    )
    updateTitle()
    dom.window.setTimeout(() => Api.unread().foreach(u => if (u != unread.v) unread := u), 300)
  }

  /** タブの題名に未読の数を出す. */
  def updateTitle(): Unit = {
    val n = channels.v.filterNot(_.muted).map(_.mentions).sum
    val base = dom.document.title.replaceAll("^\\(\\d+\\) ", "")
    dom.document.title = if (n > 0) s"($n) $base" else base
  }

  def reloadMe(): Unit = {
    memberCache = Map.empty
    Api.me().foreach(m => me := Some(m))
  }

  /** メンションの候補（チャンネルのメンバー）. */
  def members(channelId: UUID): Future[List[UserBrief]] =
    memberCache.get(channelId) match {
      case Some(a) => Future.successful(a)
      case None => Api.members(channelId).map(_.map(_.user).filterNot(_.bot)).map { a =>
          memberCache = memberCache.updated(channelId, a); a
        }
    }

  def mentionGroups(channelId: UUID): Future[List[MentionGroupDto]] =
    groupCache.get(channelId) match {
      case Some(a) => Future.successful(a)
      case None => Api.mentionGroups(channelId).map { a => groupCache = groupCache.updated(channelId, a); a }
    }

  def forgetMembers(channelId: UUID): Unit = memberCache -= channelId

  // ---------------------------------------------------------------- 画面の移動

  def openChannel(id: UUID): Unit = { sideOpen := false; Router.go(s"/c/$id") }

  /** DM を開く（相手. 空なら自分だけ）. */
  def openDm(users: List[UUID]): Unit = Toasts.report(Api.openDm(users)) { c => replace(c); openChannel(c.id) }

  /** 表示中のチャンネルを知らせる（参加していない公開チャンネルの変更も受け取る）. */
  def view(id: Option[UUID]): Unit = if (viewing != id) {
    viewing = id
    val watch = id.filterNot(i => channel(i).exists(_.member))
    if (watch != streamWatch) { stream.foreach(_.close()); stream = None; connect() }
  }

  // ---------------------------------------------------------------- 自動同期

  private var polling = false

  /** 変更を確認する. */
  def poll(): Unit = if (me.v.isDefined && !polling) {
    polling = true
    lastPoll = js.Date.now()
    Api.changes(since, viewing.filterNot(i => channel(i).exists(_.member))).onComplete { r =>
      polling = false
      r.foreach { c =>
        if (since == 0L) since = c.latest
        else if (c.events.nonEmpty) {
          since = c.latest
          eventSeq += 1
          events := ((eventSeq, c.events))
          if (c.events.exists(e => e.kind != EventKind.ReactionChanged)) reloadChannels()
          if (c.events.exists(e => (e.kind == EventKind.MemberJoined || e.kind == EventKind.MemberLeft)))
            memberCache = Map.empty
        } else since = c.latest
      }
    }
    Api.unread().foreach { u =>
      if (u.unread > unread.v.unread) desktopNotify()
      if (u != unread.v) unread := u
    }
  }

  /** 自分の操作の直後. */
  def changed(): Unit = dom.window.setTimeout(() => poll(), 200)

  /** 画面が隠れているときに、新しい通知をデスクトップに出す（許可されていれば）. */
  private def desktopNotify(): Unit =
    if (
      dom.document.visibilityState.toString != "visible" && Prefs.get("desktop", "on") == "on" &&
      !js.isUndefined(js.Dynamic.global.Notification) &&
      js.Dynamic.global.Notification.permission.asInstanceOf[String] == "granted"
    )
      Api.notifications(Nil, unread = true).foreach(_.items.headOption.foreach { n =>
        val no = js.Dynamic.newInstance(js.Dynamic.global.Notification)(
          n.title,
          js.Dynamic.literal(
            body = n.body.take(200),
            tag = n.id.toString,
            icon = "/favicon.svg"
          )
        )
        no.onclick = (
          () => {
            dom.window.focus()
            n.channel.foreach(c => Router.go(Paths.message(c.id, n.messageId, n.rootId)))
          }): js.Function0[Unit]
      })

  def askDesktopPermission(): Unit =
    if (
      !js.isUndefined(js.Dynamic.global.Notification) &&
      js.Dynamic.global.Notification.permission.asInstanceOf[String] == "default"
    )
      js.Dynamic.global.Notification.requestPermission()

  // ---------------------------------------------------------------- 即時の反映（SSE）

  private var stream: Option[dom.EventSource] = None
  private var streamWatch: Option[UUID] = None
  private var pending: Option[Int] = None
  private var lastPoll = 0.0

  /** 知らせが続けて届いてもまとめて1回だけ取りに行く. */
  private def soon(): Unit =
    if (pending.isEmpty) pending = Some(dom.window.setTimeout(() => { pending = None; poll() }, 120))

  private def live: Boolean = stream.exists(_.readyState == dom.EventSource.OPEN)

  def connect(): Unit = if (me.v.isDefined && stream.isEmpty) {
    val watch = viewing.filterNot(i => channel(i).exists(_.member))
    val es = new dom.EventSource("/api/stream" + watch.fold("")(w => s"?watch=$w"))
    es.addEventListener("change", (_: dom.MessageEvent) => soon())
    es.addEventListener("notify", (_: dom.MessageEvent) => soon())
    // つなぎ直したときは取りこぼしを確かめる
    es.addEventListener("open", (_: dom.Event) => soon())
    stream = Some(es)
    streamWatch = watch
  }

  def startSync(): Unit = {
    // SSE でつながっている間は、念のため60秒ごとにだけ確認する
    dom.window.setInterval(
      () =>
        if (dom.document.visibilityState.toString == "visible" && (!live || js.Date.now() - lastPoll > 60000)) poll(),
      10000
    )
    dom.document.addEventListener(
      "visibilitychange",
      (_: dom.Event) => if (dom.document.visibilityState.toString == "visible") poll()
    )
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs extends sbirds.web.LocalPrefs("stui")

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme extends sbirds.web.ThemeSwitch(Prefs)

/** カスタム絵文字（リアクション用. sbirds-lib の CustomEmojis）. */
object Emojis {
  export sbirds.web.CustomEmojis.{custom, image, load}
}
