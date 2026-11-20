<script lang="scala">
import stui.web.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// チャンネルを探す（公開チャンネルと、参加している非公開チャンネル. アーカイブも見られる）
case class State(search: DebouncedFn[State], q: RV[String] = RV(""), archived: RV[Boolean] = RV(false), list: RV[List[ChannelDto]] = RV(Nil))

def initState() = State(useDebounce[State](200)(load))

def mounted(s: State): Unit = { pageTitle("チャンネルを探す"); load(s) }

def load(s: State): Unit = Api.browse(s.q.v.trim, s.archived.v).foreach(s.list := _)

def typed(s: State): Unit = s.search(s)

def join(s: State, c: ChannelDto): Unit = Toasts.report(Api.join(c.id)) { n => Store.replace(n); Store.openChannel(n.id) }
</script>

<template>
  <div class="page scroll-page">
    <div class="row head"><h1 class="grow">🔍 チャンネルを探す</h1><button class="primary small" @click='Store.dialog := "channel"'>＋ チャンネルを作る</button></div>
    <div class="row bar">
      <input type="search" class="grow" v-model="s.q" placeholder="名前・トピック・説明で探す" @input="typed(s)" v-focus>
      <label class="check"><input type="checkbox" v-model="s.archived" @change="load(s)"> アーカイブ</label>
    </div>
    <div v-for="c in s.list" :key="c.id" class="ch" @click="Store.openChannel(c.id)">
      <div class="grow">
        <div><b>{{ Store.prefix(c.kind) }}{{ c.name }}</b> <span class="muted small">{{ c.memberCount }}人</span>
          <span v-if="c.member" class="joined small">参加中</span></div>
        <div class="muted small">{{ if (c.topic.nonEmpty) c.topic else c.purpose }}</div>
      </div>
      <button v-if="!c.member && !c.archived" class="small" @click.stop="join(s, c)">参加</button>
    </div>
    <p v-if="s.list.mapC(_.isEmpty)" class="muted">見つかりませんでした</p>
  </div>
</template>

<style scoped>
.head { align-items: center; }
.bar { gap: .8rem; margin-bottom: 1rem; }
.ch { display: flex; align-items: center; gap: .6rem; padding: .7rem .6rem; border-bottom: 1px solid var(--line); cursor: pointer; }
.ch:hover { background: var(--hover); }
.joined { color: var(--ok); margin-left: .3rem; }
</style>
