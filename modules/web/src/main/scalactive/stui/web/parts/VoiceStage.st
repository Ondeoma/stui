<script lang="scala">
import stui.web.*
import stui.shared.*

import java.util.UUID

// ボイスチャンネルの画面の上の部分: いる人（話している人は枠を光らせる）、画面共有、参加・操作のボタン. 下はふつうのチャット
case class Props(channel: ChannelDto)

case class State(big: RV[Boolean] = RV(false), focus: RV[Option[String]] = RV(None))

def initState() = State()

/** 画面共有の1枠（key は connId か "self"）. */
case class Share(key: String, conn: Option[UUID], name: String)

def here(props: Props) = Voice.channel.mapC(_.contains(props.channel.id))
def notHere(props: Props) = here(props).mapC(!_)
def list(props: Props) = Voice.rooms.mapC(_.getOrElse(props.channel.id, Nil))
def count(props: Props) = list(props).mapC(_.size.toString)
def empty(props: Props) = list(props).mapC(_.isEmpty)
def joinable(props: Props) = notHere(props).mapC(_ && !props.channel.archived)

/** 見られる画面共有: 自分の画面と、届いている相手の画面（共有中の人だけ）. */
def shares(props: Props): CRV[List[Share]] = computed {
  val sc = Voice.screens.v
  val mine = Voice.sharing.v
  val ms = Voice.rooms.v.getOrElse(props.channel.id, Nil)
  if (!Voice.channel.v.contains(props.channel.id)) Nil
  else
    ms.filter(m => m.sharing || Voice.isSelf(m)).flatMap { m =>
      if (Voice.isSelf(m)) Option.when(mine)(Share("self", None, "あなたの画面"))
      else sc.get(m.connId).map(_ => Share(m.connId.toString, Some(m.connId), s"${m.user.name} の画面"))
    }
}
def hasShares(props: Props) = shares(props).mapC(_.nonEmpty)

/** 大きく出す画面共有（選んだもの. なければ最初のもの）. */
def main(s: State, props: Props): CRV[List[Share]] = computed {
  val ss = shares(props).v
  s.focus.v.flatMap(k => ss.find(_.key == k)).orElse(ss.headOption).toList
}
def others(s: State, props: Props): CRV[List[Share]] = computed {
  val m = main(s, props).v
  shares(props).v.filterNot(x => m.exists(_.key == x.key))
}

/** 参加していないが、画面共有している人がいる. */
def liveOutside(props: Props): CRV[Boolean] = computed {
  !Voice.channel.v.contains(props.channel.id) && Voice.rooms.v.getOrElse(props.channel.id, Nil).exists(_.sharing)
}

def tileCls(m: VoiceMemberDto) = Voice.speaking.mapC(sp => if (sp.contains(m.connId)) "tile speaking" else "tile")
def label(m: VoiceMemberDto): String = if (Voice.isSelf(m)) s"${m.user.name}（あなた）" else m.user.name
def state(m: VoiceMemberDto): String = if (m.deafened) "🔕" else if (m.muted) "🔇" else ""
def link(m: VoiceMemberDto) = Voice.links.mapC(ls =>
  if (Voice.isSelf(m)) ""
  else ls.get(m.connId) match {
    case Some("failed") | Some("disconnected") => "接続が不安定です"
    case Some("connected") | None => ""
    case _ => "接続しています…"
  })
def stageCls(s: State, props: Props): CRV[String] = computed {
  val b = s.big.v
  List(Some("vstage"), Option.when(b)("big"), Option.when(!b && shares(props).v.nonEmpty)("with-share")).flatten.mkString(" ")
}
def bigIcon(s: State) = s.big.mapC(b => if (b) "⤡" else "⤢")
def bigTitle(s: State) = s.big.mapC(b => if (b) "チャットを広く表示" else "通話を大きく表示")
</script>

<template>
  <section :class="stageCls(s, props)">
    <div class="vhead">
      <span class="vt">🔊 ボイス</span>
      <span class="muted small">{{ count(props) }}人</span>
      <span v-if="liveOutside(props)" class="live">LIVE</span>
      <span class="grow"></span>
      <button v-if="here(props)" class="ghost small" :title="bigTitle(s)" @click="s.big := !s.big.v">{{ bigIcon(s) }}</button>
      <button v-if="joinable(props)" class="primary" :disabled="Voice.joining" @click="Voice.join(props.channel.id)">🔊 参加する</button>
    </div>

    <div v-if="hasShares(props)" class="shares">
      <div v-for="x in main(s, props)" :key="x.key" class="share main">
        <ScreenVideo :connId="x.conn" />
        <span class="slabel">🖥️ {{ x.name }}</span>
      </div>
      <div v-if="others(s, props).mapC(_.nonEmpty)" class="thumbs">
        <button v-for="x in others(s, props)" :key="x.key" class="share thumb" :title='x.name + "を大きく表示"' @click="s.focus := Some(x.key)">
          <ScreenVideo :connId="x.conn" />
          <span class="slabel">{{ x.name }}</span>
        </button>
      </div>
    </div>

    <div class="tiles">
      <div v-for="m in list(props)" :key="m.connId" :class="tileCls(m)">
        <span class="ring"><Avatar :user="Some(person(m.user))" :size="48" /></span>
        <span class="tname">{{ label(m) }}</span>
        <span class="tst"><span v-if="m.sharing" class="live">LIVE</span> {{ state(m) }}</span>
        <span v-if="here(props)" class="tlink">{{ link(m) }}</span>
      </div>
      <p v-if="empty(props)" class="muted small hint">まだ誰もいません。「参加する」で通話を始められます（画面共有もできます）。</p>
      <p v-if="liveOutside(props)" class="muted small hint">参加すると、共有されている画面を見られます。</p>
    </div>

    <div v-if="here(props)" class="ctl"><VoiceControls :large="true" /></div>
  </section>
</template>

<style scoped>
.vstage { flex: none; display: flex; flex-direction: column; gap: .5rem; padding: .6rem 1rem .7rem; border-bottom: 1px solid var(--line);
  background: var(--hover); max-height: 45%; overflow-y: auto; }
/* 画面共有中: 高さを決め、映像を残りの高さに合わせて縮める（参加者と操作のボタンはいつも見える） */
.vstage.with-share { height: 65%; max-height: none; overflow: hidden; }
.vstage.big { flex: 1 1 auto; height: auto; max-height: none; }
.vhead { display: flex; align-items: center; gap: .5rem; flex: none; }
.vt { font-weight: 700; }
.live { background: var(--danger); color: #fff; font-size: .62rem; font-weight: 800; border-radius: 4px; padding: 0 .3rem; letter-spacing: .03em; }
.shares { display: flex; flex-direction: column; gap: .4rem; flex: 1 1 auto; min-height: 0; }
.share { position: relative; border-radius: 10px; overflow: hidden; background: #000; }
.share.main { flex: 1 1 auto; min-height: 140px; }
.thumbs { display: flex; gap: .4rem; flex: none; }
.share.thumb { width: 180px; height: 100px; padding: 0; border: 2px solid transparent; flex: none; }
.share.thumb:hover { border-color: var(--accent); }
.slabel { position: absolute; left: .4rem; bottom: .3rem; background: rgba(0, 0, 0, .6); color: #fff; font-size: .75rem; border-radius: 5px;
  padding: .05rem .4rem; pointer-events: none; }
.tiles { display: flex; flex-wrap: wrap; gap: .5rem; align-items: flex-start; flex: none; }
.vstage.with-share .tiles { flex-wrap: nowrap; overflow-x: auto; }
.vstage.with-share .tile { flex-direction: row; width: auto; flex: none; padding: .25rem .6rem .25rem .3rem; }
.tile { width: 112px; display: flex; flex-direction: column; align-items: center; gap: .2rem; padding: .45rem .3rem; border-radius: 10px;
  background: var(--bg); border: 1px solid var(--line); }
.ring { display: inline-flex; border-radius: 999px; box-shadow: 0 0 0 3px transparent; transition: box-shadow .12s; }
.tile.speaking .ring { box-shadow: 0 0 0 3px #22c55e; }
.tname { font-size: .8rem; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tst { font-size: .78rem; min-height: 1em; }
.tlink { font-size: .68rem; color: var(--warn); }
.hint { margin: .2rem 0; align-self: center; }
.ctl { display: flex; justify-content: center; flex: none; }
@media (max-width: 760px) {
  .tile { width: 84px; }
}
</style>
