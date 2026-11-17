<script lang="scala">
import stui.web.*
import io.circe.Json
import org.scalajs.dom
import stui.shared.AttachmentDto

import java.util.UUID
import scala.concurrent.Future

// リッチテキストエディタ（Tiptap）. onReady でエディタの操作（中身の取得など）を親に渡す.
// enterSubmit なら Enter で送る（Shift+Enter で改行）. upload は貼り付けたファイルの受け取り（Some を返すと本文に埋め込む）.
case class Props(content: Option[Json] = None,
                 placeholder: String = "",
                 channelId: Option[UUID] = None,
                 autofocus: Boolean = false,
                 enterSubmit: Boolean = false,
                 toolbar: Boolean = true,
                 upload: Option[dom.File => Future[Option[AttachmentDto]]] = None,
                 onReady: EditorHandle => Unit = _ => (),
                 onSubmit: Unit => Unit = _ => (),
                 onArrowUp: Option[() => Unit] = None,
                 onChange: Json => Unit = _ => ())

// script はインスタンスで共有される object になるため、エディタはインスタンスごとの State に持つ
case class State(el: ElementRef[dom.HTMLElement] = ElementRef(), handle: RV[Option[EditorHandle]] = RV(None))

def initState() = State()

def mounted(s: State, props: Props): Unit =
  s.el.foreach { el =>
    val h = Editor.create(el, props.content, props.placeholder, props.channelId, () => props.onSubmit(()), props.autofocus, props.onChange,
      props.enterSubmit, props.upload, props.onArrowUp, props.toolbar)
    s.handle := Some(h)
    props.onReady(h)
  }

def unmounted(s: State): Unit = {
  s.handle.v.foreach(_.destroy())
  s.handle := None
}
</script>

<template>
  <div ref="s.el"></div>
</template>
