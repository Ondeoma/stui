<script lang="scala">
import stui.web.*
import stui.web.parts.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// アクティビティ（自分への通知）: メンション・DM・スレッドの返信・リマインダー・連携サービス. 種類と未読で絞り込む.
case class State(kind: RV[String] = RV(""), unread: RV[Boolean] = RV(false), list: RV[NotificationListDto] = RV(NotificationListDto(Nil, 0)),
                 loading: RV[Boolean] = RV(true))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("アクティビティ")
  load(s)
  watch(Store.unread, false)((_, _) => load(s))
}

def load(s: State): Unit =
  Api.notifications(Option(s.kind.v).filter(_.nonEmpty).toList, s.unread.v).foreach { l => s.list := l; s.loading := false }

def setKind(s: State, k: String): Unit = { s.kind := k; load(s) }

def open(s: State, n: NotificationDto): Unit = {
  if (!n.read) Api.postUnit("/api/notifications/read", io.circe.syntax.EncoderOps(MarkReadReq(List(n.id))).asJson).foreach(_ => Store.changed())
  n.channel.foreach(c => Router.go(Paths.message(c.id, n.messageId, n.rootId)))
}

def readAll(s: State): Unit = Store.report(Api.postUnit("/api/notifications/read-all"))(_ => { load(s); Store.changed() })

def kinds: List[(String, String)] = ("" -> "すべて") :: NotificationKind.all.map(k => k -> NotificationKind.label(k))
def kindCls(s: State, k: String) = s.kind.mapC(x => if (x == k) "chip on" else "chip")
def icon(k: String): String = k match {
  case NotificationKind.Mention  => "＠"
  case NotificationKind.Dm       => "💬"
  case NotificationKind.Thread   => ""
  case NotificationKind.Channel  => "#"
  case NotificationKind.Reminder => "⏰"
  case _                         => "🔗"
}
def iconCls(k: String): String = if (k == NotificationKind.Thread) "ico ico-thread" else ""
def items(s: State) = s.list.mapC(_.items)
def empty(s: State) = computed { s.list.v.items.isEmpty && !s.loading.v }
</script>

<template>
  <div class="page scroll-page">
    <div class="row head">
      <h1 class="grow">🔔 アクティビティ</h1>
      <label class="check"><input type="checkbox" v-model="s.unread" @change="load(s)"> 未読だけ</label>
      <button class="small" @click="readAll(s)">すべて既読にする</button>
    </div>
    <div class="chips">
      <button v-for="k in kinds" :class="kindCls(s, k._1)" @click="setKind(s, k._1)">{{ k._2 }}</button>
    </div>
    <p v-if="empty(s)" class="muted">通知はありません</p>
    <div v-for="n in items(s)" :key="n.id" :class='if (n.read) "n" else "n unread"' @click="open(s, n)">
      <span class="ic"><span :class="iconCls(n.kind)">{{ icon(n.kind) }}</span></span>
      <Avatar :user="n.actor" :size="30" />
      <div class="grow body">
        <div><b>{{ n.title }}</b> <span class="muted small">{{ Fmt.ago(n.at) }}</span></div>
        <div class="muted text">{{ n.body }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.head { align-items: center; gap: .8rem; }
.chips { display: flex; gap: .3rem; flex-wrap: wrap; margin-bottom: 1rem; }
.chip { border-radius: 999px; padding: .2rem .8rem; font-size: .85rem; }
.chip.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.n { display: flex; gap: .6rem; align-items: flex-start; padding: .7rem .6rem; border-bottom: 1px solid var(--line); cursor: pointer; border-radius: 8px; }
.n:hover { background: var(--hover); }
.n.unread { background: var(--accent-weak); }
.ic { width: 1.4rem; text-align: center; color: var(--muted); }
.body { min-width: 0; }
.text { font-size: .9rem; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 4.5em; overflow: hidden; }
</style>
