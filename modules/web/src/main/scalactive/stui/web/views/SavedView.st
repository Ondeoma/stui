<script lang="scala">
import stui.web.*
import stui.web.parts.*
import stui.shared.*

// 保存したメッセージ（個人のブックマーク）
case class State(list: AsyncState[List[MessageInChannelDto]])

def initState() = State(useAsync(Api.saved()))

def mounted(s: State): Unit = pageTitle("保存済み")

def unsave(s: State, x: MessageInChannelDto): Unit =
  Toasts.report(Api.save(x.message.id, on = false)){ _ => s.list.update(_.filterNot(_.message.id == x.message.id)); Store.refreshSaved() }
def items(s: State) = s.list.data.mapC(_.getOrElse(Nil))
def empty(s: State) = s.list.data.mapC(_.exists(_.isEmpty))
</script>

<template>
  <div class="page scroll-page">
    <h1>🔖 保存済み</h1>
    <p v-if="s.list.loading" class="muted">読み込み中…</p>
    <p v-if="empty(s)" class="muted">保存したメッセージはありません。メッセージにマウスを乗せて、しおりのボタンを押すと保存できます。</p>
    <div v-for="x in items(s)" :key="x.message.id" class="item">
      <ResultItem :m="x.message" :channel="x.channel" />
      <button class="small ghost un" @click="unsave(s, x)">保存を外す</button>
    </div>
  </div>
</template>

<style scoped>
.item { position: relative; }
.un { position: absolute; top: .4rem; right: .5rem; }
</style>
