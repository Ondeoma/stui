<script lang="scala">
import org.scalajs.dom

import scala.scalajs.js

// モーダルの枠（body の直下に描く。背景のクリックか Esc キーで onClose を呼ぶ）
case class Props(onClose: Unit => Unit, wide: Boolean = false, xwide: Boolean = false, slot: Slot = Slot.empty)

case class State(esc: RV[Option[js.Function1[dom.KeyboardEvent, Unit]]] = RV(None))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  val f: js.Function1[dom.KeyboardEvent, Unit] = (e: dom.KeyboardEvent) =>
    // エディタの候補の一覧などが Esc を使ったときは閉じない
    if (e.key == "Escape" && !e.defaultPrevented) props.onClose(())
  dom.window.addEventListener("keydown", f)
  s.esc := Some(f)
}

def unmounted(s: State): Unit = {
  s.esc.v.foreach(f => dom.window.removeEventListener("keydown", f))
  s.esc := None
}

def cls(props: Props): String = if (props.xwide) "modal xwide" else if (props.wide) "modal wide" else "modal"
</script>

<template>
  <Teleport to="body">
    <div class="modal-bg" @click.self="props.onClose(())">
      <div :class="cls(props)"><slot></slot></div>
    </div>
  </Teleport>
</template>
