<script lang="scala">
import stui.web.*
import stui.web.parts.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// フォロー中のスレッド（新しい返信の順. 未読の返信の数つき）
case class State(list: RV[List[ThreadSummaryDto]] = RV(Nil), loading: RV[Boolean] = RV(true))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("スレッド")
  load(s)
  watch(Store.events, false)((_, ev) => if (ev._2.exists(_.rootId.isDefined)) load(s))
}

def load(s: State): Unit = Api.threads().foreach { ts => s.list := ts; s.loading := false }

def unreadLabel(t: ThreadSummaryDto): String = if (t.unread > 0) s"未読 ${t.unread}" else ""
def empty(s: State) = computed { s.list.v.isEmpty && !s.loading.v }
</script>

<template>
  <div class="page scroll-page">
    <h1><span class="ico ico-thread"></span> スレッド</h1>
    <p v-if="s.loading" class="muted">読み込み中…</p>
    <p v-if="empty(s)" class="muted">フォローしているスレッドはありません。スレッドに返信するか、メンションされると、ここに出ます。</p>
    <div v-for="t in s.list" :key="t.root.id" class="th">
      <ResultItem :m="t.root" :channel="t.channel" :thread="true" :extra='s"${t.root.replyCount}件の返信 " + unreadLabel(t)' />
      <div v-for="r in t.lastReplies" :key="r.id" class="last muted small" @click="Router.go(Paths.message(t.channel.id, Some(r.id), Some(t.root.id)))">↳ <b>{{ r.author.map(_.name).getOrElse("") }}</b>: {{ Html.text(r.html).take(120) }}</div>
    </div>
  </div>
</template>

<style scoped>
.th { margin-bottom: 1rem; }
.last { padding-left: 1.2rem; margin-top: -.3rem; margin-bottom: .3rem; cursor: pointer; }
.last:hover { color: var(--fg); }
</style>
