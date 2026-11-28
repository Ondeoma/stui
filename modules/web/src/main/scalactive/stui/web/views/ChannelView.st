<script lang="scala">
import stui.web.*
import stui.web.parts.*
import io.circe.syntax.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// チャンネル・DM の画面: ヘッダー（名前・トピック・ブックマーク・ピン留め・メンバー・会議）、メッセージの一覧、入力欄、右のスレッド・詳細
case class Props(channelId: UUID, threadRoot: Option[UUID] = None)

/** 一覧の1行（表示に使う値をまとめて持つ. 値が変わった行だけ描き直される）. */
case class Row(m: MessageDto, compact: Boolean, editing: Boolean, hl: Boolean, day: Option[String], newLine: Boolean, canPost: Boolean,
               canManage: Boolean)

case class State(channel: RV[Option[ChannelDto]] = RV(None),
                 list: RV[List[MessageDto]] = RV(Nil),
                 hasMore: RV[Boolean] = RV(false),
                 hasNewer: RV[Boolean] = RV(false),
                 loading: RV[Boolean] = RV(true),
                 error: RV[String] = RV(""),
                 thread: RV[Option[UUID]] = RV(None),
                 pane: RV[String] = RV(""),
                 editing: RV[Option[UUID]] = RV(None),
                 highlight: RV[Option[UUID]] = RV(None),
                 threadHighlight: RV[Option[UUID]] = RV(None),
                 readUpTo: RV[Long] = RV(0L),
                 bookmarks: RV[List[BookmarkDto]] = RV(Nil),
                 pins: RV[List[MessageDto]] = RV(Nil),
                 members: RV[List[ChannelMemberDto]] = RV(Nil),
                 settings: RV[Boolean] = RV(false),
                 addBookmark: RV[Boolean] = RV(false),
                 bmTitle: RV[String] = RV(""),
                 bmUrl: RV[String] = RV(""),
                 atBottom: RV[Boolean] = RV(true),
                 scroller: ElementRef[dom.HTMLElement] = ElementRef(),
                 loadingOlder: RV[Boolean] = RV(false),
                 readTimer: RV[Option[Int]] = RV(None))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  Store.view(Some(props.channelId))
  Prefs.set("last", props.channelId.toString)
  val target = Router.queryParam("m").v.flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
  s.readUpTo := Store.channel(props.channelId).map(_.lastReadSeq).getOrElse(Long.MaxValue)
  s.thread := props.threadRoot
  if (props.threadRoot.isDefined) { s.pane := "thread"; s.threadHighlight := target }
  loadChannel(s, props)
  target.filter(_ => props.threadRoot.isEmpty) match {
    case Some(m) =>
      Api.message(m).flatMap(x => Api.messages(props.channelId, around = Some(x.seq))).onComplete {
        case scala.util.Success(p) =>
          setPage(s, p); s.highlight := Some(m)
          dom.window.setTimeout(() => Option(dom.document.getElementById(s"msg-$m")).foreach(_.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "center"))), 60)
        case scala.util.Failure(_) => loadLatest(s, props)
      }
    case None => loadLatest(s, props)
  }
  Api.bookmarks(props.channelId).foreach(s.bookmarks := _)
  watch(Store.events, false)((_, ev) => onEvents(s, props, ev._2))
  // タブに戻ったら既読にする（画面を離れると自動で外れる）
  useEventListener[dom.Event](dom.document, "visibilitychange") { _ =>
    if (dom.document.visibilityState.toString == "visible") markRead(s, props)
  }
  ()
}

def unmounted(s: State, props: Props): Unit = {
  s.readTimer.v.foreach(dom.window.clearTimeout)
  Store.view(None)
}

def loadChannel(s: State, props: Props): Unit =
  Api.channel(props.channelId).onComplete {
    case scala.util.Success(c) =>
      s.channel := Some(c)
      pageTitle(Store.title(c))
      Store.view(Some(props.channelId))
    case scala.util.Failure(e) => s.error := e.getMessage
  }

def setPage(s: State, p: MessagePageDto): Unit = {
  s.list := p.messages
  s.hasMore := p.hasMore
  s.hasNewer := p.hasNewer
  s.loading := false
}

def loadLatest(s: State, props: Props): Unit =
  Api.messages(props.channelId).onComplete {
    case scala.util.Success(p) =>
      setPage(s, p)
      s.highlight := None
      scrollBottom(s)
      markRead(s, props)
    case scala.util.Failure(e) => s.loading := false; s.error := e.getMessage
  }

def scrollBottom(s: State): Unit =
  dom.window.setTimeout(() => s.scroller.foreach(el => el.scrollTop = el.scrollHeight.toDouble), 30)

/** 一覧の上の端で、古いメッセージを読む（表示の位置を保つ）. */
def onScroll(s: State, props: Props): Unit = s.scroller.foreach { el =>
  val bottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  if (bottom != s.atBottom.v) s.atBottom := bottom
  if (bottom && !s.hasNewer.v) markRead(s, props)
  if (el.scrollTop < 120 && s.hasMore.v && !s.loadingOlder.v) {
    s.loadingOlder := true
    val before = s.list.v.headOption.map(_.seq)
    val h0 = el.scrollHeight
    Api.messages(props.channelId, before = before).foreach { p =>
      s.list := p.messages ++ s.list.v.filterNot(m => p.messages.exists(_.id == m.id))
      s.hasMore := p.hasMore
      dom.window.setTimeout(() => { el.scrollTop = el.scrollTop + (el.scrollHeight - h0); s.loadingOlder := false }, 0)
    }
  }
  if (bottom && s.hasNewer.v && !s.loadingOlder.v) {
    s.loadingOlder := true
    Api.messages(props.channelId, after = s.list.v.lastOption.map(_.seq)).foreach { p =>
      s.list := s.list.v ++ p.messages.filterNot(m => s.list.v.exists(_.id == m.id))
      s.hasNewer := p.hasNewer
      s.loadingOlder := false
    }
  }
}

/** 見えている最新のメッセージまで既読にする（画面が表示されていて、一番下にいるとき）. */
def markRead(s: State, props: Props): Unit =
  if (dom.document.visibilityState.toString == "visible" && s.atBottom.v && !s.hasNewer.v && s.channel.v.exists(_.member)) {
    s.readTimer.v.foreach(dom.window.clearTimeout)
    s.readTimer := Some(dom.window.setTimeout(() => {
      s.readTimer := None
      val seq = s.list.v.map(_.seq).maxOption.getOrElse(0L)
      val c = Store.channel(props.channelId)
      if (c.exists(x => x.unread > 0 || x.mentions > 0 || x.lastReadSeq < seq))
        Api.markRead(props.channelId, seq).foreach(_ => Store.markedRead(props.channelId, seq))
    }, 400))
  }

/** 同期で届いた変更のうち、このチャンネルのものを取り直す. */
def onEvents(s: State, props: Props, evs: List[ChatEventDto]): Unit = {
  val mine = evs.filter(_.channelId == props.channelId)
  if (mine.nonEmpty) {
    if (mine.exists(e => e.kind == EventKind.ChannelUpdated || e.kind == EventKind.MemberJoined || e.kind == EventKind.MemberLeft)) {
      loadChannel(s, props)
      if (s.pane.v == "members") loadMembers(s, props)
    }
    if (mine.exists(_.kind == EventKind.BookmarkChanged)) Api.bookmarks(props.channelId).foreach(s.bookmarks := _)
    val ids = mine.flatMap(_.messageId).distinct
    if (ids.nonEmpty && !s.hasNewer.v) Api.batch(ids).foreach(ms => merge(s, props, ms))
    if (s.pane.v == "pins" && mine.exists(_.kind == EventKind.MessageUpdated)) loadPins(s, props)
  }
}

/** 取り直したメッセージを一覧に入れる（新しいものは足し、変わったものは差し替え、消えたものは除く）. */
def merge(s: State, props: Props, ms: List[MessageDto]): Unit = {
  val wasBottom = s.atBottom.v
  var list = s.list.v
  ms.foreach { m =>
    val visible = m.rootId.isEmpty || m.alsoChannel
    val gone = m.deleted && m.replyCount == 0
    if (list.exists(_.id == m.id)) list = if (gone) list.filterNot(_.id == m.id) else list.map(x => if (x.id == m.id) m else x)
    // 表示している範囲（一番古いものより新しい）に入るものだけ足す（さらに古いものはさかのぼったときに読む）
    else if (visible && !gone && (list.isEmpty || !s.hasMore.v || list.head.seq < m.seq)) list = (list :+ m).sortBy(_.seq)
  }
  if (list != s.list.v) {
    s.list := list
    if (wasBottom) { scrollBottom(s); markRead(s, props) }
  }
}

def sent(s: State, props: Props, m: MessageDto): Unit = {
  if (m.rootId.isEmpty || m.alsoChannel) {
    if (s.hasNewer.v) loadLatest(s, props)
    else { merge(s, props, List(m)); scrollBottom(s) }
  }
  s.readUpTo := math.max(s.readUpTo.v, m.seq)
}

def replace(s: State, m: MessageDto): Unit = s.list := s.list.v.map(x => if (x.id == m.id) m else x)
def removed(s: State, id: UUID): Unit = s.list := s.list.v.filterNot(x => x.id == id && x.replyCount == 0).map(x => if (x.id == id) x.copy(deleted = true) else x)

def openThread(s: State, props: Props, m: MessageDto): Unit = {
  val root = m.rootId.getOrElse(m.id)
  s.thread := Some(root)
  s.threadHighlight := None
  s.pane := "thread"
  dom.window.history.replaceState(null, "", Paths.thread(props.channelId, root))
}

def closePane(s: State, props: Props): Unit = {
  s.pane := ""
  s.thread := None
  dom.window.history.replaceState(null, "", s"/c/${props.channelId}")
}

def togglePane(s: State, props: Props, p: String): Unit =
  if (s.pane.v == p) closePane(s, props)
  else {
    s.pane := p
    if (p == "pins") loadPins(s, props)
    if (p == "members") loadMembers(s, props)
  }

def loadPins(s: State, props: Props): Unit = Api.pins(props.channelId).foreach(s.pins := _)
def loadMembers(s: State, props: Props): Unit = Api.members(props.channelId).foreach(s.members := _)

def join(s: State, props: Props): Unit = Toasts.report(Api.join(props.channelId)) { c =>
  s.channel := Some(c); Store.replace(c); Store.view(Some(props.channelId))
}

def favorite(s: State, props: Props): Unit = s.channel.v.foreach { c =>
  Toasts.report(Api.prefs(props.channelId, MemberPrefReq(Some(!c.favorite), None, None, None))) { n => s.channel := Some(n); Store.replace(n) }
}

def editLast(s: State): Unit =
  s.list.v.reverse.find(m => m.author.exists(a => Store.myId.contains(a.id)) && m.kind == "user" && !m.deleted).foreach(m => s.editing := Some(m.id))

def saveBookmark(s: State, props: Props): Unit =
  Toasts.report(Api.post[List[BookmarkDto]](s"/api/channels/${props.channelId}/bookmarks", BookmarkReq(s.bmTitle.v, s.bmUrl.v, None).asJson)) { bs =>
    s.bookmarks := bs; s.addBookmark := false; s.bmTitle := ""; s.bmUrl := ""
  }

def removeBookmark(s: State, props: Props, b: BookmarkDto): Unit = Dialog.confirm(s"ブックマーク「${b.title}」を外しますか？", "外す") {
  Toasts.report(Api.delete(s"/api/channels/${props.channelId}/bookmarks/${b.id}"))(_ => s.bookmarks := s.bookmarks.v.filterNot(_.id == b.id))
}

def openMember(u: UserBrief): Unit = if (!u.bot) Store.openDm(List(u.id))

/** 1つ前のメッセージと同じ人の続けての投稿か（5分以内）. */
def compact(s: State, i: Int): Boolean = {
  val l = s.list.v
  i > 0 && {
    val (p, m) = (l(i - 1), l(i))
    p.kind != "system" && m.kind != "system" && p.author.map(_.id) == m.author.map(_.id) && p.props.hcursor.get[String]("username").toOption ==
      m.props.hcursor.get[String]("username").toOption && m.createdAt.toEpochMilli - p.createdAt.toEpochMilli < 5 * 60 * 1000 &&
      Fmt.dayKey(p.createdAt) == Fmt.dayKey(m.createdAt) && !m.alsoChannel && !newDivider(s, i)
  }
}

def dayHead(s: State, i: Int): Option[String] = {
  val l = s.list.v
  Option.when(i == 0 || Fmt.dayKey(l(i - 1).createdAt) != Fmt.dayKey(l(i).createdAt))(Fmt.dayLabel(l(i).createdAt))
}

/** ここから未読（開いたときに読んでいた位置の次の、人の投稿）. */
def newDivider(s: State, i: Int): Boolean = {
  val l = s.list.v
  val m = l(i)
  m.seq > s.readUpTo.v && !m.author.exists(a => Store.myId.contains(a.id)) && (i == 0 || l(i - 1).seq <= s.readUpTo.v)
}

def rows(s: State) = computed {
  val e = s.editing.v
  val h = s.highlight.v
  val post = s.channel.v.exists(c => c.member && !c.archived)
  val manage = s.channel.v.exists(_.role.contains(ChannelRole.Admin)) || Store.me.v.exists(_.isAdmin)
  s.readUpTo.v
  s.list.v.zipWithIndex.map((m, i) => Row(m, compact(s, i), e.contains(m.id), h.contains(m.id), dayHead(s, i), newDivider(s, i), post, manage))
}
def loaded(s: State) = s.channel.mapC(_.isDefined)
def showIntro(s: State) = computed { !s.hasMore.v && !s.loading.v }
def showJump(s: State) = computed { !s.atBottom.v && !s.loading.v }
def showBookmarks(s: State) = computed { s.bookmarks.v.nonEmpty || s.addBookmark.v }
def memberNotDm(s: State) = s.channel.mapC(_.exists(c => c.member && !ChannelKind.isDm(c.kind)))
def notDm(s: State) = s.channel.mapC(_.exists(c => !ChannelKind.isDm(c.kind)))
def canPost(s: State) = s.channel.mapC(_.exists(c => c.member && !c.archived))
def canManage(s: State) = s.channel.mapC(c => c.exists(_.role.contains(ChannelRole.Admin)) || Store.me.v.exists(_.isAdmin))
def isMember(s: State) = s.channel.mapC(_.exists(_.member))
def title(s: State) = s.channel.mapC(_.map(Store.title).getOrElse(""))
def icon(s: State) = s.channel.mapC(_.map(Store.icon).getOrElse(""))
def voiceChannel(s: State) = s.channel.mapC(_.filter(_.voice).toList)
def topic(s: State) = s.channel.mapC(_.map(c => if (c.topic.nonEmpty) c.topic else c.purpose).getOrElse(""))
def memberCount(s: State) = s.channel.mapC(_.map(_.memberCount.toString).getOrElse(""))
def isDm(s: State) = s.channel.mapC(_.exists(c => ChannelKind.isDm(c.kind)))
def favIcon(s: State) = s.channel.mapC(c => if (c.exists(_.favorite)) "★" else "☆")
def placeholder(s: State) = s.channel.mapC(_.map(c => s"${if (ChannelKind.isDm(c.kind)) Store.title(c) + " さん" else "#" + c.name} へのメッセージ（@でメンション、#でチャンネル、/でメニュー）").getOrElse(""))
def archived(s: State) = s.channel.mapC(_.exists(_.archived))
def paneClass(s: State) = s.pane.mapC(p => if (p.isEmpty) "layout" else "layout with-pane")
def tabCls(s: State, p: String) = s.pane.mapC(x => if (x == p) "ghost hb active" else "ghost hb")
def meetings = Store.config.mapC(_.exists(_.meetings))
</script>

<template>
  <div :class="paneClass(s)">
    <section class="chan">
      <header class="chead">
        <div class="ctitle">
          <span class="cicon">{{ icon(s) }}</span><h1>{{ title(s) }}</h1>
          <button v-if="isMember(s)" class="ghost star" title="お気に入り" @click="favorite(s, props)">{{ favIcon(s) }}</button>
          <span class="topic" :title="topic(s)">{{ topic(s) }}</span>
        </div>
        <div class="hbtns">
          <button :class='tabCls(s, "members")' title="メンバー" @click='togglePane(s, props, "members")'>👤 {{ memberCount(s) }}</button>
          <button :class='tabCls(s, "pins")' title="ピン留め" @click='togglePane(s, props, "pins")'>📌</button>
          <button class="ghost hb gear" title="チャンネルの設定" @click="s.settings := true">⚙</button>
        </div>
      </header>
      <div v-if="showBookmarks(s)" class="bookmarks">
        <span v-for="b in s.bookmarks" :key="b.id" class="bm">
          <a :href="b.url" target="_blank" rel="noopener" v-rich="b.url">{{ if (b.emoji.nonEmpty) b.emoji else "🔗" }} {{ b.title }}</a>
          <button class="bm-x" title="外す" @click="removeBookmark(s, props, b)">×</button>
        </span>
        <form v-if="s.addBookmark" class="bm-form" @submit.prevent="saveBookmark(s, props)">
          <input type="text" v-model="s.bmTitle" placeholder="名前" class="small-in">
          <input type="url" v-model="s.bmUrl" placeholder="https://…" class="small-in">
          <button class="small primary" type="submit">追加</button><button class="small" type="button" @click="s.addBookmark := false">やめる</button>
        </form>
      </div>
      <VoiceStage v-for="c in voiceChannel(s)" :key="c.id" :channel="c" />
      <div class="list" ref="s.scroller" @scroll="onScroll(s, props)">
        <p v-if="s.error.mapC(_.nonEmpty)" class="error pad">{{ s.error }}</p>
        <div v-if="s.loading" class="muted pad">読み込み中…</div>
        <div v-if="s.loadingOlder" class="muted small center">読み込み中…</div>
        <div v-if="showIntro(s)" class="intro">
          <div class="big">{{ icon(s) }}</div>
          <h2>{{ title(s) }}</h2>
          <p class="muted">{{ topic(s) }}</p>
          <div class="row intro-btns">
            <button v-if="memberNotDm(s)" class="small" @click="s.addBookmark := true">🔖 ブックマークを追加</button>
            <button v-if="memberNotDm(s)" class="small" @click="s.settings := true">👤 メンバーを追加</button>
          </div>
        </div>
        <template v-for="r in rows(s)" :key="r.m.id">
          <div v-if="r.day.isDefined" class="day"><span>{{ r.day.getOrElse("") }}</span></div>
          <div v-if="r.newLine" class="new-line"><span>新着</span></div>
          <MessageItem :m="r.m" :compact="r.compact" :canPost="r.canPost" :canManage="r.canManage" :highlight="r.hl"
            :editing="r.editing" :onEdit="e => s.editing := e" :onReply="m => openThread(s, props, m)" :onChanged="m => replace(s, m)"
            :onRemoved="id => removed(s, id)" />
        </template>
        <div v-if="s.hasNewer" class="center pad"><button class="small" @click="loadLatest(s, props)">最新のメッセージへ</button></div>
      </div>
      <div v-if="showJump(s)" class="jump"><button class="small" @click="scrollBottom(s)">↓ 最新へ</button></div>
      <Composer v-if="canPost(s)" :channelId="props.channelId" :placeholder="placeholder(s).v" :onSent="m => sent(s, props, m)" :onEditLast="() => editLast(s)" />
      <div v-if="s.channel.mapC(_.exists(c => !c.member))" class="joinbar">
        <span class="muted">このチャンネルを見ています（参加していません）</span>
        <button class="primary" @click="join(s, props)">参加する</button>
      </div>
      <div v-if="archived(s)" class="joinbar"><span class="muted">このチャンネルはアーカイブされています（投稿できません）</span></div>
    </section>

    <aside v-if='s.pane.mapC(_ == "thread")' class="pane">
      <ThreadPane v-for="r in s.thread.mapC(_.toList)" :key="r.toString" :rootId="r" :channelId="props.channelId" :highlight="s.threadHighlight.v"
        :canPost="canPost(s).v" :canManage="canManage(s).v" :onClose="_ => closePane(s, props)" />
    </aside>
    <aside v-if='s.pane.mapC(_ == "pins")' class="pane">
      <div class="phead"><b>📌 ピン留め</b><span class="grow"></span><button class="ghost" @click="closePane(s, props)">✕</button></div>
      <div class="pbody">
        <p v-if="s.pins.mapC(_.isEmpty)" class="muted pad">ピン留めしたメッセージはありません。メッセージの「⋯」→「ピン留め」で追加できます。</p>
        <div v-for="m in s.pins" :key="m.id" class="pin-item">
          <MessageItem :m="m" :canPost="canPost(s).v" :canManage="canManage(s).v" :onReply="x => openThread(s, props, x)"
            :onChanged="_ => loadPins(s, props)" :onRemoved="_ => loadPins(s, props)" />
        </div>
      </div>
    </aside>
    <aside v-if='s.pane.mapC(_ == "members")' class="pane">
      <div class="phead"><b>👤 メンバー（{{ s.members.mapC(_.size.toString) }}）</b><span class="grow"></span><button class="ghost" @click="closePane(s, props)">✕</button></div>
      <div class="pbody">
        <button v-if="memberNotDm(s)" class="small add-m" @click="s.settings := true">＋ メンバーを追加</button>
        <div v-for="m in s.members" :key="m.user.id" class="member" @click="openMember(m.user)">
          <Avatar :user="Some(person(m.user))" :size="28" /><span class="grow">{{ m.user.name }}</span>
          <span v-if="m.user.bot" class="muted small">APP</span>
          <span v-if='m.role == "admin"' class="muted small">管理者</span>
        </div>
      </div>
    </aside>
  </div>
  <template v-if="s.settings">
    <ChannelSettings v-for="c in s.channel.mapC(_.toList)" :channel="c" :onClose="_ => s.settings := false" :onChanged="n => s.channel := Some(n)" />
  </template>
</template>

<style scoped>
.layout { flex: 1; display: flex; min-height: 0; }
.chan { flex: 1; min-width: 0; display: flex; flex-direction: column; position: relative; }
.chead { display: flex; align-items: center; gap: .5rem; padding: .45rem 1rem; border-bottom: 1px solid var(--line); flex: none; min-height: 50px; }
.ctitle { flex: 1; min-width: 0; display: flex; align-items: center; gap: .4rem; }
.cicon { color: var(--muted); font-weight: 700; }
h1 { font-size: 1.05rem; margin: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 40%; }
.star { padding: 0 .3rem; color: var(--warn); }
.topic { color: var(--muted); font-size: .85rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; border-left: 1px solid var(--line);
  padding-left: .6rem; }
.hbtns { display: flex; gap: .2rem; flex: none; }
.hb { padding: .25rem .5rem; font-size: .9rem; }
.hb.active { background: var(--accent-weak); color: var(--accent); }
.hb.gear { font-size: 1.35rem; line-height: 1; padding: .1rem .45rem; }
.bookmarks { display: flex; gap: .4rem; flex-wrap: wrap; align-items: center; padding: .3rem 1rem; border-bottom: 1px solid var(--line); flex: none; }
.bm { display: inline-flex; align-items: center; font-size: .82rem; border-radius: 6px; padding: .05rem .2rem .05rem .4rem; }
.bm:hover { background: var(--hover); }
.bm a { color: var(--fg); }
.bm-x { border: none; background: none; padding: 0 .25rem; font-size: .8rem; opacity: 0; }
.bm:hover .bm-x { opacity: .7; }
.bm-form { display: flex; gap: .3rem; }
.small-in { font-size: .8rem; padding: .2rem .4rem; width: 12rem; }
.list { flex: 1; overflow-y: auto; min-height: 0; padding: .5rem 0 1rem; overflow-anchor: none; }
.pad { padding: 1rem; }
.center { text-align: center; }
.intro { padding: 2rem 1.2rem 1rem; }
.intro .big { font-size: 2.4rem; color: var(--muted); }
.intro h2 { margin: .2rem 0; }
.intro-btns { gap: .4rem; margin-top: .6rem; }
.day { position: sticky; top: 0; z-index: 3; display: flex; justify-content: center; margin: .7rem 0 .2rem; }
.day span { background: var(--bg); border: 1px solid var(--line); border-radius: 999px; padding: .1rem .8rem; font-size: .78rem; font-weight: 700; }
.new-line { display: flex; align-items: center; gap: .5rem; margin: .2rem 1rem; color: var(--danger); font-size: .75rem; font-weight: 700; }
.new-line::before { content: ""; flex: 1; border-top: 1px solid var(--danger); }
.jump { position: absolute; bottom: 9.5rem; left: 50%; transform: translateX(-50%); z-index: 6; }
.jump button { box-shadow: var(--shadow); border-radius: 999px; background: var(--bg); }
.joinbar { display: flex; justify-content: center; align-items: center; gap: 1rem; padding: 1rem; border-top: 1px solid var(--line); flex: none; }
.pane { width: 420px; flex: none; border-left: 1px solid var(--line); display: flex; flex-direction: column; min-height: 0; background: var(--bg); }
.phead { display: flex; align-items: center; padding: .6rem 1rem; border-bottom: 1px solid var(--line); min-height: 50px; flex: none; }
.pbody { flex: 1; overflow-y: auto; min-height: 0; padding: .4rem 0; }
.pin-item { border-bottom: 1px solid var(--line); }
.member { display: flex; align-items: center; gap: .6rem; padding: .4rem 1rem; cursor: pointer; }
.member:hover { background: var(--hover); }
.add-m { margin: .3rem 1rem .6rem; }
@media (max-width: 1100px) { .pane { width: 360px; } }
@media (max-width: 900px) {
  .with-pane .chan { display: none; }
  .pane { width: 100%; border-left: none; }
  .topic { display: none; }
  h1 { max-width: none; }
}
</style>
