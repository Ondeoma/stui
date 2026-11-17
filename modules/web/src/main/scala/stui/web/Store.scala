package stui.web

import io.circe.Json
import io.circe.parser.parse
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.{Failure, Success}

final case class Toast(id: Int, text: String, link: Option[String], error: Boolean)

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
  val toasts: RV[List[Toast]] = RV(Nil)
  /** サイドバーを開いているか（スマホの幅）. */
  val sideOpen: RV[Boolean] = RV(false)

  /** 取得した変更の束（番号, 変更）. 各画面はこれを監視する. */
  val events: RV[(Int, List[ChatEventDto])] = RV((0, Nil))

  /** 開いているダイアログ（チャンネルの作成・DM の作成）. */
  val dialog: RV[String] = RV("")

  private var since: Long = 0L
  private var toastSeq = 0
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

  def prefix(kind: String): String = if (ChannelKind.isDm(kind)) "" else if (kind == ChannelKind.Private) "🔒 " else "# "

  // ---------------------------------------------------------------- トースト

  def toast(text: String, link: Option[String] = None, error: Boolean = false): Unit = {
    toastSeq += 1
    val t = Toast(toastSeq, text, link, error)
    toasts := (toasts.v :+ t).takeRight(5)
    dom.window.setTimeout(() => dismiss(t.id), if (error) 9000 else 6000)
  }

  def dismiss(id: Int): Unit = toasts := toasts.v.filterNot(_.id == id)

  def error(e: Throwable): Unit = e match {
    case ApiError(401, _) => ()
    case other            => toast(other.getMessage, error = true)
  }

  /** Future の失敗をトーストで知らせる. */
  def report[A](f: Future[A])(ok: A => Unit): Unit = f.onComplete {
    case Success(a) => ok(a)
    case Failure(e) => error(e)
  }

  // ---------------------------------------------------------------- 読み込み

  private var reloadTimer: Option[Int] = None

  /** チャンネルの一覧を読み直す（続けて呼ばれてもまとめて1回）. */
  def reloadChannels(): Unit = if (reloadTimer.isEmpty)
    reloadTimer = Some(dom.window.setTimeout(() => {
      reloadTimer = None
      Api.channels().foreach { cs => channels := cs; updateTitle() }
      Api.unreadThreads().foreach(n => if (n != threadsUnread.v) threadsUnread := n)
      refreshSaved()
    }, 200))

  /** 保存済みの件数を読み直す（サイドバーに出す）. */
  def refreshSaved(): Unit = Api.savedCount().foreach(n => if (n != savedCount.v) savedCount := n)

  def loadChannels(): Future[List[ChannelDto]] = { refreshSaved(); Api.channels().map { cs => channels := cs; updateTitle(); cs } }

  /** 一覧の中のチャンネルを差し替える（設定を変えたとき）. */
  def replace(c: ChannelDto): Unit =
    channels := (if (channels.v.exists(_.id == c.id)) channels.v.map(x => if (x.id == c.id) c else x) else if (c.member) channels.v :+ c else channels.v)

  /** 既読にした（一覧の未読の数を消す）. */
  def markedRead(id: UUID, seq: Long): Unit = {
    channels := channels.v.map(c => if (c.id == id) c.copy(unread = 0, mentions = 0, lastReadSeq = math.max(c.lastReadSeq, seq)) else c)
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
      case None    => Api.members(channelId).map(_.map(_.user).filterNot(_.bot)).map { a => memberCache = memberCache.updated(channelId, a); a }
    }

  def mentionGroups(channelId: UUID): Future[List[MentionGroupDto]] =
    groupCache.get(channelId) match {
      case Some(a) => Future.successful(a)
      case None    => Api.mentionGroups(channelId).map { a => groupCache = groupCache.updated(channelId, a); a }
    }

  def forgetMembers(channelId: UUID): Unit = memberCache -= channelId

  // ---------------------------------------------------------------- 画面の移動

  def openChannel(id: UUID): Unit = { sideOpen := false; Router.go(s"/c/$id") }

  /** DM を開く（相手. 空なら自分だけ）. */
  def openDm(users: List[UUID]): Unit = report(Api.openDm(users)) { c => replace(c); openChannel(c.id) }

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
          if (c.events.exists(e => (e.kind == EventKind.MemberJoined || e.kind == EventKind.MemberLeft))) memberCache = Map.empty
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
    if (dom.document.visibilityState.toString != "visible" && Prefs.get("desktop", "on") == "on" &&
        !js.isUndefined(js.Dynamic.global.Notification) && js.Dynamic.global.Notification.permission.asInstanceOf[String] == "granted")
      Api.notifications(Nil, unread = true).foreach(_.items.headOption.foreach { n =>
        val no = js.Dynamic.newInstance(js.Dynamic.global.Notification)(n.title, js.Dynamic.literal(body = n.body.take(200), tag = n.id.toString,
          icon = "/favicon.svg"))
        no.onclick = (() => {
          dom.window.focus()
          n.channel.foreach(c => Router.go(Paths.message(c.id, n.messageId, n.rootId)))
        }): js.Function0[Unit]
      })

  def askDesktopPermission(): Unit =
    if (!js.isUndefined(js.Dynamic.global.Notification) && js.Dynamic.global.Notification.permission.asInstanceOf[String] == "default")
      js.Dynamic.global.Notification.requestPermission()

  // ---------------------------------------------------------------- 即時の反映（SSE）

  private var stream: Option[dom.EventSource] = None
  private var streamWatch: Option[UUID] = None
  private var pending: Option[Int] = None
  private var lastPoll = 0.0

  /** 知らせが続けて届いてもまとめて1回だけ取りに行く. */
  private def soon(): Unit = if (pending.isEmpty) pending = Some(dom.window.setTimeout(() => { pending = None; poll() }, 120))

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
    dom.window.setInterval(() => if (dom.document.visibilityState.toString == "visible" && (!live || js.Date.now() - lastPoll > 60000)) poll(), 10000)
    dom.document.addEventListener("visibilitychange", (_: dom.Event) => if (dom.document.visibilityState.toString == "visible") poll())
  }

}

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  /** 押した要素の下に、スクロールする枠（なければ画面）の端までどれだけ余白があるか（px）. メニューを上下どちらに開くかに使う. */
  def spaceBelow(e: dom.Event): Double = {
    val el = e.currentTarget.asInstanceOf[dom.HTMLElement]
    val bottom = el.getBoundingClientRect().bottom
    def scroller(n: dom.HTMLElement): Option[dom.HTMLElement] =
      if (n == null) None
      else {
        val oy = dom.window.getComputedStyle(n).overflowY
        if (oy == "auto" || oy == "scroll") Some(n) else scroller(n.parentElement)
      }
    val limit = scroller(el.parentElement).map(_.getBoundingClientRect().bottom).getOrElse(dom.window.innerHeight.toDouble)
    limit - bottom
  }

  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

}

/** 表示の補助. */
object Fmt {

  private def opts(o: (String, String)*): js.Dynamic = js.Dynamic.literal(o.map((k, v) => k -> (v: js.Any))*)

  def dateTime(t: Timestamp): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString("ja-JP", opts("year" -> "numeric", "month" -> "2-digit", "day" -> "2-digit",
      "hour" -> "2-digit", "minute" -> "2-digit")).asInstanceOf[String]

  /** 「3分前」「昨日」など. */
  def ago(t: Timestamp): String = {
    val diff = (js.Date.now() - t.toEpochMilli) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 7) s"${(diff / 86400).toInt}日前"
    else dateTime(t).take(10)
  }

  /** 2026-11-15 → 11/15(日). */
  def day(d: String): String = {
    val dt = new js.Date(d + "T00:00:00")
    val w  = "日月火水木金土".charAt(dt.getDay().toInt)
    s"${dt.getMonth().toInt + 1}/${dt.getDate().toInt}($w)"
  }

  def today(): String = isoDate(new js.Date())

  /** 今の時刻（13:05）. */
  def clock(): String = { val d = new js.Date(); f"${d.getHours().toInt}%02d:${d.getMinutes().toInt}%02d" }

  def isoDate(d: js.Date): String = f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  def addDays(d: String, n: Int): String = {
    val dt = new js.Date(d + "T00:00:00")
    dt.setDate(dt.getDate() + n)
    isoDate(dt)
  }

  def initials(name: String): String = name.trim.take(1)

  def bytes(n: Long): String =
    if (n < 1024) s"$n B" else if (n < 1024 * 1024) f"${n / 1024.0}%.1f KB" else f"${n / 1024.0 / 1024.0}%.1f MB"

  /** 時刻だけ（13:05）. */
  def time(t: Timestamp): String = {
    val d = t.toDate
    f"${d.getHours().toInt}%02d:${d.getMinutes().toInt}%02d"
  }

  /** 日付の区切り（今日・昨日・11月15日(日)・2025年11月15日(土)）. */
  def dayLabel(t: Timestamp): String = {
    val d = t.toDate
    val today = new js.Date()
    val key = isoDate(d)
    val w = "日月火水木金土".charAt(d.getDay().toInt)
    if (key == isoDate(today)) "今日"
    else if (key == addDays(isoDate(today), -1)) "昨日"
    else if (d.getFullYear() == today.getFullYear()) s"${d.getMonth().toInt + 1}月${d.getDate().toInt}日($w)"
    else s"${d.getFullYear().toInt}年${d.getMonth().toInt + 1}月${d.getDate().toInt}日($w)"
  }

  def dayKey(t: Timestamp): String = isoDate(t.toDate)

  /** 定期メッセージのくり返し（「毎週 水曜日 09:00」）. */
  def schedule(x: ScheduledDto): String = {
    val when = x.frequency match {
      case Frequency.Once    => x.runDate.getOrElse("")
      case Frequency.Weekly  => Frequency.weekdays.find(w => x.weekday.contains(w._1)).map(_._2 + "曜日").getOrElse("")
      case Frequency.Monthly => x.monthday.fold("")(d => s"${d}日")
      case _                 => ""
    }
    List(Frequency.label(x.frequency), when, x.runTime).filter(_.nonEmpty).mkString(" ")
  }

}

/** エディタ（frontend/src/editor.js の Tiptap）. */
@js.native
@JSGlobal("stuiEditor")
private object EditorFacade extends js.Object {
  def create(el: dom.Element, opts: js.Object): EditorHandle = js.native
}

@js.native
trait EditorHandle extends js.Object {
  def getJSON(): js.Any = js.native
  def setJSON(j: js.Any): Unit = js.native
  def isEmpty(): Boolean = js.native
  def clear(): Unit = js.native
  def focus(): Unit = js.native
  def insertText(t: String): Unit = js.native
  def pickFile(): Unit = js.native
  def destroy(): Unit = js.native
}

object Editor {

  import scala.scalajs.js.JSConverters.*

  def toJs(j: Json): js.Any = js.JSON.parse(j.noSpaces)

  def fromJs(a: js.Any): Json = parse(js.JSON.stringify(a)).getOrElse(Json.Null)

  /**
   * エディタを作る. channelId を渡すと @メンションの候補をそのチャンネルのメンバーから出す.
   * upload を渡すと、画像・ファイルの貼り付けでそれを呼ぶ（Some を返すと本文に埋め込む. None なら埋め込まない）.
   */
  def create(el: dom.Element, content: Option[Json], placeholder: String, channelId: Option[UUID], onSubmit: () => Unit,
             autofocus: Boolean = false, onChange: Json => Unit = _ => (), enterSubmit: Boolean = false,
             upload: Option[dom.File => Future[Option[AttachmentDto]]] = None, onArrowUp: Option[() => Unit] = None,
             toolbar: Boolean = true): EditorHandle = {
    // メンションの候補: 人と、まとめて届くグループ（「グループ・3人」のように出す）
    val mentions: js.Function1[String, js.Promise[js.Array[js.Object]]] = (q: String) => {
      val ql = q.toLowerCase
      val people = channelId.fold(Api.users(q))(Store.members)
      val groups = channelId.fold(Future.successful(List.empty[MentionGroupDto]))(c => Store.mentionGroups(c).recover(_ => Nil))
      people.zip(groups).map { (us, gs) =>
        (us.filter(u => ql.isEmpty || u.name.toLowerCase.contains(ql)).map(u => js.Dynamic.literal(id = u.id.toString, label = u.name)) ++
          gs.filter(g => ql.isEmpty || g.name.toLowerCase.contains(ql)).map(g => js.Dynamic.literal(id = g.id, label = g.name, icon = "👥",
            hint = s"${if (g.id.startsWith("r:")) "チャンネルの全員" else "グループ"}・${g.count}人")))
          .map(_.asInstanceOf[js.Object]).toJSArray
      }.toJSPromise
    }
    val channels: js.Function1[String, js.Promise[js.Array[js.Object]]] = (q: String) => {
      val ql = q.toLowerCase
      Future.successful(Store.channels.v.filterNot(c => ChannelKind.isDm(c.kind)).filter(c => ql.isEmpty || c.name.toLowerCase.contains(ql))
        .map(c => js.Dynamic.literal(id = c.id.toString, label = c.name, icon = if (c.kind == ChannelKind.Private) "🔒" else "#").asInstanceOf[js.Object])
        .toJSArray).toJSPromise
    }
    val up: js.Any = upload match {
      case None => null
      case Some(f) =>
        val g: js.Function1[dom.File, js.Promise[js.Any]] = (file: dom.File) =>
          f(file).map {
            case Some(a) => js.Dynamic.literal(url = a.url, name = a.fileName, image = a.contentType.startsWith("image/")).asInstanceOf[js.Any]
            case None    => null.asInstanceOf[js.Any]
          }.toJSPromise
        g
    }
    val o = js.Dynamic.literal(
      content = content.map(toJs).getOrElse(null),
      placeholder = placeholder,
      mentions = mentions,
      channels = channels,
      upload = up,
      autofocus = autofocus,
      enterSubmit = enterSubmit,
      toolbar = toolbar,
      onSubmit = (() => onSubmit()): js.Function0[Unit],
      onChange = ((j: js.Any) => onChange(fromJs(j))): js.Function1[js.Any, Unit],
      onArrowUp = onArrowUp.fold(null: js.Any)(f => (() => f()): js.Function0[Unit])
    )
    EditorFacade.create(el, o.asInstanceOf[js.Object])
  }

}

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String, okLabel: String, danger: Boolean, input: Option[RV[String]], choices: List[(String, String)],
                           cancel: Boolean, onOk: String => Unit, onCancel: () => Unit)

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(r: DialogReq): Unit = current := Some(r)

  /** 確認（OK を押したら f）. */
  def confirm(message: String, ok: String = "OK", danger: Boolean = true)(f: => Unit): Unit =
    open(DialogReq(message, ok, danger, None, Nil, cancel = true, _ => f, () => ()))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(message: String, default: String = "", ok: String = "OK")(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), Nil, cancel = true, f, () => ()))

  /** 選択肢から選ぶ（choices は 値 → 表示名）. */
  def choose(message: String, choices: List[(String, String)], ok: String = "OK", danger: Boolean = false)(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger, Some(RV(choices.headOption.map(_._1).getOrElse(""))), choices, cancel = true, f, () => ()))

  def alert(message: String): Unit = open(DialogReq(message, "OK", danger = false, None, Nil, cancel = false, _ => (), () => ()))

  def close(ok: Boolean): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

  /** エディタ（JavaScript）から使う: window.stuiUi.prompt(message, default) → Promise(文字列 | null)、alert(message). */
  def exposeToJs(): Unit = {
    val prompt: js.Function2[String, String, js.Promise[js.Any]] = (message: String, default: String) =>
      new js.Promise[js.Any]((resolve, _) =>
        open(DialogReq(message, "OK", danger = false, Some(RV(Option(default).getOrElse(""))), Nil, cancel = true,
          v => resolve(v), () => resolve(null))))
    val alertF: js.Function1[String, Unit] = (m: String) => alert(m)
    dom.window.asInstanceOf[js.Dynamic].stuiUi = js.Dynamic.literal(prompt = prompt, alert = alertF)
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs {

  def get(key: String, default: String): String =
    scala.util.Try(Option(dom.window.localStorage.getItem("stui." + key))).toOption.flatten.getOrElse(default)

  def set(key: String, value: String): Unit = scala.util.Try(dom.window.localStorage.setItem("stui." + key, value))

}

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme {

  val current: RV[String] = RV(Prefs.get("theme", "system"))

  def apply(): Unit = {
    val el = dom.document.documentElement
    if (current.v == "system") el.removeAttribute("data-theme") else el.setAttribute("data-theme", current.v)
  }

  def set(v: String): Unit = { current := v; Prefs.set("theme", v); apply() }

  val options: List[(String, String)] = List("system" -> "🖥 自動（OS に合わせる）", "light" -> "☀️ ライト", "dark" -> "🌙 ダーク")

}

/** オートコンプリート（parts/Combo.st）の選択肢. icon は前に付ける文字、user はアイコン、color はラベルの色. */
final case class Opt(value: String, label: String, icon: String = "", user: Option[UserBrief] = None, color: String = "", hint: String = "")

object Opt {
  def users(us: List[UserBrief]): List[Opt] = us.map(u => Opt(u.id.toString, u.name, user = Some(u)))
  def of(xs: List[(String, String)]): List[Opt] = xs.map((v, l) => Opt(v, l))
  /** 値と表示が同じ選択肢. */
  def names(xs: List[String]): List[Opt] = xs.map(x => Opt(x, x))
}

/** カスタム絵文字（リアクション用. 一度だけ読む）. */
object Emojis {

  val custom: RV[List[CustomEmojiDto]] = RV(Nil)
  private var loaded = false

  def load(force: Boolean = false): Unit = if (!loaded || force) {
    loaded = true
    Api.get[List[CustomEmojiDto]]("/api/emoji").foreach(custom := _)
  }

  /** ":name:" ならカスタム絵文字の画像の URL. */
  def image(e: String): Option[String] = Reactions.customName(e).map(CustomEmoji.url)

}

/** 外部サービス（skiwi・skawau・skea）へのリンクに名前を出す（frontend/src/links.js. 連携したサービスだけ動かす）. */
object LinkTitles {

  private var handles = Map.empty[String, js.Dynamic]

  def start(svc: LinkServiceDto): Unit = if (svc.connected) handles.get(svc.provider) match {
    case Some(h) => h.reset()
    case None    => handles += svc.provider -> dom.window.asInstanceOf[js.Dynamic].stuiLinks.start(svc.provider, svc.url)
  }

  /** 連携しているサービスを問い合わせて動かす. */
  def load(): scala.concurrent.Future[List[LinkServiceDto]] = {
    import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
    val f = Api.get[List[LinkServiceDto]]("/api/integrations/links")
    f.foreach(_.foreach(start))
    f
  }

}
