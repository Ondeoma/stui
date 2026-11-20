<script lang="scala">
import stui.web.*
import stui.shared.*

// 入力途中の下書き（チャンネル・スレッドごと）
case class State(list: AsyncState[List[DraftListItemDto]])

def initState() = State(useAsync(Api.drafts()))

def mounted(s: State): Unit = pageTitle("下書き")

def open(d: DraftListItemDto): Unit = Router.go(Paths.message(d.channel.id, None, d.rootId))

def discard(s: State, d: DraftListItemDto): Unit = Dialog.confirm("この下書きを破棄しますか？", "破棄する") {
  Toasts.report(Api.deleteDraft(d.channel.id, d.rootId))(_ => s.list.update(_.filterNot(x => x.channel.id == d.channel.id && x.rootId == d.rootId)))
}
def items(s: State) = s.list.data.mapC(_.getOrElse(Nil))
def empty(s: State) = s.list.data.mapC(_.exists(_.isEmpty))
</script>

<template>
  <div class="page scroll-page">
    <h1>✏️ 下書き</h1>
    <p v-if="s.list.loading" class="muted">読み込み中…</p>
    <p v-if="empty(s)" class="muted">下書きはありません。入力途中のメッセージは自動で保存されます。</p>
    <div v-for="d in items(s)" class="draft" @click="open(d)">
      <div class="muted small">{{ Store.prefix(d.channel.kind) }}{{ Store.titleOf(d.channel) }}{{ if (d.rootId.isDefined) "（スレッド）" else "" }}・{{ Fmt.ago(d.updatedAt) }}</div>
      <div class="row"><span class="grow">{{ d.text }}</span><button class="small ghost" @click.stop="discard(s, d)">破棄</button></div>
    </div>
  </div>
</template>

<style scoped>
.draft { border: 1px solid var(--line); border-radius: 10px; padding: .6rem .8rem; margin-bottom: .6rem; cursor: pointer; background: var(--card); }
.draft:hover { border-color: var(--accent); }
</style>
