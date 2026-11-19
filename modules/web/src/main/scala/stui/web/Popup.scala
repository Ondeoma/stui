package stui.web

import org.scalajs.dom

import scala.scalajs.js

/** 入力中の文字（@ や # や /）の下に出す候補の一覧（Tiptap の Suggestion の render）. 項目は {label, icon?, iconClass?, hint?}. */
private object Popup {

  /** 開いている一覧の数（Enter で送るとき、候補を選ぶ Enter と区別する）. */
  private var open = 0

  def isOpen: Boolean = open > 0

  private def text(v: js.Dynamic): Option[String] =
    Option(v).filterNot(js.isUndefined).map(_.toString).filter(_.nonEmpty)

  def render(emptyText: String): js.Function0[js.Object] = () => {
    var box: Option[dom.HTMLElement] = None
    var items = js.Array[js.Dynamic]()
    var selected = 0
    var command: js.Dynamic = null
    def span(cls: String, t: String = ""): dom.HTMLElement = {
      val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
      if (cls.nonEmpty) s.className = cls
      if (t.nonEmpty) s.textContent = t
      s
    }
    def draw(): Unit = box.foreach { bx =>
      bx.innerHTML = ""
      if (items.isEmpty) {
        val e = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
        e.className = "sk-mention-empty"
        e.textContent = emptyText
        bx.appendChild(e)
      } else items.zipWithIndex.foreach {
        (it, i) =>
          val b = dom.document.createElement("button").asInstanceOf[dom.HTMLButtonElement]
          b.`type` = "button"
          b.className = "sk-mention-item" + (if (i == selected) " selected" else "")
          (text(it.iconClass), text(it.icon)) match {
            case (Some(c), _) => val ic = span("sk-pop-icon"); ic.appendChild(span(c)); b.appendChild(ic)
            case (_, Some(t)) => b.appendChild(span("sk-pop-icon", t))
            case _ => ()
          }
          val l = span(""); l.textContent = it.label.toString; b.appendChild(l)
          text(it.hint).foreach(h => b.appendChild(span("sk-pop-hint", h)))
          b.addEventListener("mousedown", (ev: dom.Event) => { ev.preventDefault(); if (command != null) command(it) })
          bx.appendChild(b)
          if (i == selected) b.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
      }
    }
    def place(rect: js.Dynamic): Unit = box.foreach { bx =>
      if (rect != null && !js.isUndefined(rect)) {
        val r = rect()
        if (r != null && !js.isUndefined(r)) {
          val w = dom.window
          val (left, top, bottom) =
            (r.left.asInstanceOf[Double], r.top.asInstanceOf[Double], r.bottom.asInstanceOf[Double])
          bx.style.left = s"${math.min(left + w.scrollX, w.scrollX + w.innerWidth - 260)}px"
          // 画面の下のほう（チャットの入力欄）では上に出す
          val below = w.innerHeight - bottom
          if (below < 260 && top > below) {
            bx.style.top = ""
            bx.style.bottom = s"${w.innerHeight - top - w.scrollY + 4}px"
          } else {
            bx.style.bottom = ""
            bx.style.top = s"${bottom + w.scrollY + 4}px"
          }
        }
      }
    }
    def take(props: js.Dynamic): Unit = {
      items = props.items.asInstanceOf[js.Array[js.Dynamic]]; selected = 0; command = props.command
      draw(); place(props.clientRect)
    }
    js.Dynamic.literal(
      onStart = { (props: js.Dynamic) =>
        open += 1
        val bx = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
        bx.className = "sk-mention-box"
        dom.document.body.appendChild(bx)
        box = Some(bx)
        take(props)
      }: js.Function1[js.Dynamic, Unit],
      onUpdate = ((props: js.Dynamic) => take(props)): js.Function1[js.Dynamic, Unit],
      onKeyDown = { (p: js.Dynamic) =>
        val n = math.max(items.length, 1)
        p.event.key.toString match {
          case "ArrowDown" => selected = (selected + 1) % n; draw(); true
          case "ArrowUp" => selected = (selected + items.length - 1) % n; draw(); true
          case "Enter" | "Tab" if selected < items.length => command(items(selected)); true
          case "Escape" => box.foreach(_.remove()); box = None; true
          case _ => false
        }
      }: js.Function1[js.Dynamic, Boolean],
      onExit = { (_: js.Dynamic) =>
        open = math.max(0, open - 1)
        box.foreach(_.remove()); box = None
      }: js.Function1[js.Dynamic, Unit]
    ).asInstanceOf[js.Object]
  }

}
