<script lang="scala">
import stui.web.*

// サイドバーの下の、つながっているボイスチャンネル（ほかのチャンネルを見ていても操作できる）
def connected = Voice.channel.mapC(_.isDefined)
def name = Voice.current.mapC(_.map(_.name).getOrElse(""))
def waiting = Voice.links.mapC(_.values.exists(st => st != "connected"))
def status = waiting.mapC(w => if (w) "接続しています…" else "ボイスに接続中")
def statusCls = waiting.mapC(w => if (w) "state wait" else "state")
def open(): Unit = Voice.channel.v.foreach(Store.openChannel)
</script>

<template>
  <div v-if="connected" class="vbar">
    <a href="#" class="vinfo" title="ボイスチャンネルを開く" @click.prevent="open()">
      <span :class="statusCls">{{ status }}</span>
      <span class="vname">🔊 {{ name }}</span>
    </a>
    <VoiceControls />
  </div>
</template>

<style scoped>
.vbar { flex: none; border-top: 1px solid var(--side-line); padding: .5rem .6rem; display: flex; flex-direction: column; gap: .4rem; }
.vinfo { display: flex; flex-direction: column; color: var(--side-fg); min-width: 0; }
.vinfo:hover { text-decoration: none; }
.state { color: #4ade80; font-weight: 700; font-size: .8rem; }
.state.wait { color: var(--warn); }
.vname { font-size: .82rem; color: var(--side-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
