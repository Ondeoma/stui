<script lang="scala">
import stui.web.*
import stui.shared.*

import java.util.UUID

// サイドバーの、ボイスチャンネルにいる人（話している人は枠を光らせる）
case class Props(channelId: UUID)

def list(props: Props) = Voice.rooms.mapC(_.getOrElse(props.channelId, Nil))
def cls(m: VoiceMemberDto) = Voice.speaking.mapC(sp => if (sp.contains(m.connId)) "vm speaking" else "vm")
def state(m: VoiceMemberDto): String = if (m.deafened) "🔕" else if (m.muted) "🔇" else ""
</script>

<template>
  <div class="vms">
    <div v-for="m in list(props)" :key="m.connId" :class="cls(m)" :title="m.user.name">
      <span class="av"><Avatar :user="Some(person(m.user))" :size="18" /></span>
      <span class="nm">{{ m.user.name }}</span>
      <span v-if="m.sharing" class="live">LIVE</span>
      <span class="st">{{ state(m) }}</span>
    </div>
  </div>
</template>

<style scoped>
.vms { padding: 0 0 .2rem 1.6rem; }
.vm { display: flex; align-items: center; gap: .4rem; padding: .12rem .4rem; font-size: .82rem; color: var(--side-muted); border-radius: 6px; }
.av { display: inline-flex; border-radius: 999px; box-shadow: 0 0 0 2px transparent; transition: box-shadow .12s; }
.vm.speaking .av { box-shadow: 0 0 0 2px #22c55e; }
.vm.speaking { color: var(--side-fg); }
.nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.live { background: var(--danger); color: #fff; font-size: .58rem; font-weight: 800; border-radius: 4px; padding: 0 .25rem; letter-spacing: .03em; }
.st { font-size: .75rem; }
</style>
