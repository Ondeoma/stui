<script lang="scala">
import stui.web.*
import stui.web.parts.*
import stui.shared.*

// フォロー中のスレッド（新しい返信の順. 未読の返信の数つき）
case class State(list: AsyncState[List[ThreadSummaryDto]])

def initState() = State(useAsync(Api.threads()))

def mounted(s: State): Unit = {
  pageTitle("スレッド")
  watch(Store.events, false)((_, ev) => if (ev._2.exists(_.rootId.isDefined)) s.list.reload())
}

def unreadLabel(t: ThreadSummaryDto): String = if (t.unread > 0) s"未読 ${t.unread}" else ""
def items(s: State) = s.list.data.mapC(_.getOrElse(Nil))
def empty(s: State) = s.list.data.mapC(_.exists(_.isEmpty))
def loading(s: State) = s.list.ready.mapC(!_)
</script>

<template>
  <div class="page scroll-page">
    <h1><span class="ico ico-thread"></span> スレッド</h1>
    <p v-if="loading(s)" class="muted">読み込み中…</p>
    <p v-if="empty(s)" class="muted">フォローしているスレッドはありません。スレッドに返信するか、メンションされると、ここに出ます。</p>
    <div v-for="t in items(s)" :key="t.root.id" class="th">
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
