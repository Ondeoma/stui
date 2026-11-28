<script lang="scala">
import stui.web.*
import org.scalajs.dom

import java.util.UUID
import scala.scalajs.js

// 画面共有の映像（connId が None なら自分の画面）. 音は Voice が別に鳴らすので video はいつも消音. ダブルクリックで全画面
case class Props(connId: Option[UUID])

case class State(video: ElementRef[dom.HTMLVideoElement] = ElementRef())

def initState() = State()

def stream(props: Props): Option[js.Dynamic] = props.connId.fold(Voice.ownScreen)(c => Voice.screens.v.get(c))

def attach(s: State, props: Props): Unit = s.video.foreach { v =>
  val want = stream(props).orNull
  val d = v.asInstanceOf[js.Dynamic]
  if (d.srcObject != want) d.srcObject = want
  if (want != null) scala.util.Try(d.play().asInstanceOf[js.Promise[Unit]].`catch`((_: Any) => ()))
}

def mounted(s: State, props: Props): Unit = {
  attach(s, props)
  watch(Voice.screens)((_, _) => attach(s, props))
  watch(Voice.sharing)((_, _) => attach(s, props))
}

def full(s: State): Unit = s.video.foreach(v => scala.util.Try(v.asInstanceOf[js.Dynamic].requestFullscreen()))
</script>

<template>
  <video ref="s.video" class="sv" autoplay playsinline muted title="ダブルクリックで全画面" @dblclick="full(s)"></video>
</template>

<style scoped>
.sv { width: 100%; height: 100%; object-fit: contain; background: #000; display: block; }
</style>
