<script lang="scala">
import stui.web.*

// ボイスチャンネルの操作（マイク・スピーカー・画面共有・切断）. サイドバーの下とボイスチャンネルの画面で使う
case class Props(large: Boolean = false)

def micOff: CRV[Boolean] = computed(Voice.muted.v || Voice.noMic.v)
def micCls(props: Props) = micOff.mapC(m => cls(props, if (m) "vc off" else "vc"))
def deafCls(props: Props) = Voice.deafened.mapC(d => cls(props, if (d) "vc off" else "vc"))
def shareCls(props: Props) = Voice.sharing.mapC(on => cls(props, if (on) "vc on" else "vc"))
def cls(props: Props, c: String): String = if (props.large) c + " large" else c
def micIcon = micOff.mapC(m => if (m) "🔇" else "🎙️")
def micTitle: CRV[String] = computed {
  if (Voice.noMic.v) "マイクを使えません" else if (Voice.muted.v) "マイクをオンにする（Ctrl+Shift+M）" else "マイクをオフにする（Ctrl+Shift+M）"
}
def deafIcon = Voice.deafened.mapC(d => if (d) "🔕" else "🎧")
def deafTitle = Voice.deafened.mapC(d => if (d) "スピーカーをオンにする（Ctrl+Shift+D）" else "スピーカーをオフにする（Ctrl+Shift+D）")
def shareTitle = Voice.sharing.mapC(on => if (on) "画面共有をやめる" else "画面を共有する")
def leaveCls(props: Props) = cls(props, "vc leave")
</script>

<template>
  <div class="vcs">
    <button :class="micCls(props)" :title="micTitle" :disabled="Voice.noMic" @click="Voice.toggleMute()">{{ micIcon }}</button>
    <button :class="deafCls(props)" :title="deafTitle" @click="Voice.toggleDeafen()">{{ deafIcon }}</button>
    <button v-if="Voice.canShare" :class="shareCls(props)" :title="shareTitle" @click="Voice.toggleShare()">🖥️</button>
    <button :class="leaveCls(props)" title="ボイスチャンネルから出る" @click="Voice.leave()">📞</button>
  </div>
</template>

<style scoped>
.vcs { display: flex; gap: .3rem; align-items: center; }
.vc { border: none; border-radius: 8px; padding: .25rem .45rem; font-size: 1rem; line-height: 1.2; background: var(--side-hover); color: var(--side-fg); }
.vc.large { padding: .5rem .8rem; font-size: 1.25rem; border-radius: 999px; background: var(--hover); color: var(--fg); }
.vc:hover { filter: brightness(1.15); }
.vc.off { background: rgba(239, 68, 68, .25); }
.vc.on { background: rgba(34, 197, 94, .35); }
.vc.leave { background: var(--danger); color: #fff; }
.vc:disabled { opacity: .5; }
</style>
