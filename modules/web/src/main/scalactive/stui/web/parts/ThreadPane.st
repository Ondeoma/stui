<script lang="scala">
import stui.web.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// スレッド（親のメッセージと返信、返信の入力欄、フォロー）. 右の欄に出す.
case class Props(rootId: UUID, channelId: UUID, highlight: Option[UUID] = None, canPost: Boolean = true, canManage: Boolean = false,
                 onClose: Unit => Unit = _ => ())

case class TRow(m: MessageDto, editing: Boolean, hl: Boolean)

case class State(root: RV[Option[MessageDto]] = RV(None),
                 replies: RV[List[MessageDto]] = RV(Nil),
                 following: RV[Boolean] = RV(false),
                 editing: RV[Option[UUID]] = RV(None),
                 error: RV[String] = RV(""),
                 scroller: ElementRef[dom.HTMLElement] = ElementRef())

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  load(s, props, scroll = true)
  watch(Store.events, false)((_, ev) => {
    val ids = ev._2.filter(e => e.rootId.contains(props.rootId) || e.messageId.contains(props.rootId)).flatMap(_.messageId).distinct
    if (ids.nonEmpty) Api.batch(ids).foreach(ms => merge(s, props, ms))
  })
}

def load(s: State, props: Props, scroll: Boolean = false): Unit =
  Api.thread(props.rootId).onComplete {
    case scala.util.Success(t) =>
      s.root := Some(t.root)
      s.replies := t.replies
      s.following := t.following
      Api.threadRead(props.rootId).foreach(_ => Store.reloadChannels())
      if (scroll) dom.window.setTimeout(() => props.highlight match {
        case Some(h) => Option(dom.document.getElementById(s"tmsg-$h")).foreach(_.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "center")))
        case None    => s.scroller.foreach(el => el.scrollTop = el.scrollHeight.toDouble)
      }, 60)
    case scala.util.Failure(e) => s.error := e.getMessage
  }

def merge(s: State, props: Props, ms: List[MessageDto]): Unit = {
  ms.foreach { m =>
    if (m.id == props.rootId) s.root := Some(m)
    else if (m.rootId.contains(props.rootId)) {
      if (m.deleted) s.replies := s.replies.v.filterNot(_.id == m.id)
      else if (s.replies.v.exists(_.id == m.id)) s.replies := s.replies.v.map(x => if (x.id == m.id) m else x)
      else s.replies := (s.replies.v :+ m).sortBy(_.seq)
    }
  }
  if (dom.document.visibilityState.toString == "visible") Api.threadRead(props.rootId)
  s.scroller.foreach(el => if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) dom.window.setTimeout(() => el.scrollTop = el.scrollHeight.toDouble, 30))
}

def sent(s: State, m: MessageDto): Unit = {
  if (!s.replies.v.exists(_.id == m.id)) s.replies := s.replies.v :+ m
  s.following := true
  s.scroller.foreach(el => dom.window.setTimeout(() => el.scrollTop = el.scrollHeight.toDouble, 30))
}

def toggleFollow(s: State, props: Props): Unit = Store.report(Api.follow(props.rootId, !s.following.v)) { _ =>
  s.following := !s.following.v
  Store.toast(if (s.following.v) "スレッドをフォローしました（返信を通知します）" else "フォローをやめました")
}

def editLast(s: State): Unit =
  s.replies.v.reverse.find(m => m.author.exists(a => Store.myId.contains(a.id)) && !m.deleted).foreach(m => s.editing := Some(m.id))

def rows(s: State, props: Props) = computed {
  val e = s.editing.v
  s.replies.v.map(m => TRow(m, e.contains(m.id), props.highlight.contains(m.id)))
}
def rootRows(s: State) = computed { val e = s.editing.v; s.root.v.toList.map(r => TRow(r, e.contains(r.id), false)) }
def count(s: State) = s.replies.mapC(r => s"${r.size}件の返信")
def followLabel(s: State) = s.following.mapC(f => if (f) "フォロー中" else "フォローする")
def channelName(props: Props): String = Store.channel(props.channelId).map(c => (if (ChannelKind.isDm(c.kind)) "" else "#") + Store.title(c)).getOrElse("")
</script>

<template>
  <div class="phead">
    <div class="grow"><b>スレッド</b> <span class="muted small">{{ channelName(props) }}</span></div>
    <button class="ghost small" @click="toggleFollow(s, props)">{{ followLabel(s) }}</button>
    <button class="ghost" title="閉じる" @click="props.onClose(())">✕</button>
  </div>
  <div class="tbody" ref="s.scroller">
    <p v-if="s.error.mapC(_.nonEmpty)" class="error pad">{{ s.error }}</p>
    <div v-for="r in rootRows(s)" :key="r.m.id" class="root">
      <MessageItem :m="r.m" :inThread="true" :canPost="props.canPost" :canManage="props.canManage" :editing="r.editing"
        :onEdit="e => s.editing := e" :onChanged="m => s.root := Some(m)" :onRemoved="_ => load(s, props)" />
    </div>
    <div class="count"><span>{{ count(s) }}</span></div>
    <div v-for="r in rows(s, props)" :key="r.m.id" :id='"tmsg-" + r.m.id'>
      <MessageItem :m="r.m" :inThread="true" :canPost="props.canPost" :canManage="props.canManage" :highlight="r.hl" :editing="r.editing"
        :onEdit="e => s.editing := e" :onChanged="m => merge(s, props, List(m))" :onRemoved="id => s.replies := s.replies.v.filterNot(_.id == id)" />
    </div>
  </div>
  <Composer v-if="props.canPost" :channelId="props.channelId" :rootId="Some(props.rootId)" placeholder="返信する…" :onSent="m => sent(s, m)"
    :onEditLast="() => editLast(s)" />
</template>

<style scoped>
.phead { display: flex; align-items: center; gap: .4rem; padding: .6rem 1rem; border-bottom: 1px solid var(--line); min-height: 50px; flex: none; }
.tbody { flex: 1; overflow-y: auto; min-height: 0; padding: .4rem 0 1rem; }
.pad { padding: 1rem; }
.root { padding-bottom: .3rem; }
.count { display: flex; align-items: center; gap: .6rem; margin: .3rem 1rem .5rem; color: var(--muted); font-size: .8rem; }
.count::after { content: ""; flex: 1; border-top: 1px solid var(--line); }
</style>
