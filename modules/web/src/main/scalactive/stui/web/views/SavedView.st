<script lang="scala">
import stui.web.*
import stui.web.parts.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 保存したメッセージ（個人のブックマーク）
case class State(list: RV[List[MessageInChannelDto]] = RV(Nil), loading: RV[Boolean] = RV(true))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("保存済み"); Api.saved().foreach { xs => s.list := xs; s.loading := false } }

def unsave(s: State, x: MessageInChannelDto): Unit =
  Store.report(Api.save(x.message.id, on = false)){ _ => s.list := s.list.v.filterNot(_.message.id == x.message.id); Store.refreshSaved() }
def empty(s: State) = computed { s.list.v.isEmpty && !s.loading.v }
</script>

<template>
  <div class="page scroll-page">
    <h1>🔖 保存済み</h1>
    <p v-if="s.loading" class="muted">読み込み中…</p>
    <p v-if="empty(s)" class="muted">保存したメッセージはありません。メッセージにマウスを乗せて、しおりのボタンを押すと保存できます。</p>
    <div v-for="x in s.list" :key="x.message.id" class="item">
      <ResultItem :m="x.message" :channel="x.channel" />
      <button class="small ghost un" @click="unsave(s, x)">保存を外す</button>
    </div>
  </div>
</template>

<style scoped>
.item { position: relative; }
.un { position: absolute; top: .4rem; right: .5rem; }
</style>
