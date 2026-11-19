package stui.web

import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * サーバーが組み立てた HTML（.rich）のコードブロックのシンタックスハイライト.
 * v-html で描き直されても付くよう、DOM の変化を見て未処理のコードブロックだけに付ける（言語定義はそのとき読み込む）.
 * ハイライトそのものは frontend/src/lowlight.js（window.stuiLowlight）.
 */
object Highlight {

  // main.js が Scala.js の読み込みの後に置くので、window のプロパティとして読む（js.Dynamic.global だと ReferenceError になる）
  private def lib: js.UndefOr[js.Dynamic] =
    dom.window.asInstanceOf[js.Dynamic].stuiLowlight.asInstanceOf[js.UndefOr[js.Dynamic]]

  private val LangRe = js.RegExp("language-([A-Za-z0-9_+-]+)")

  private def truthy(
    v: js.Any
  ): Boolean = js.DynamicImplicits.truthValue(v.asInstanceOf[js.Dynamic])

  /** lowlight の結果（hast）を DOM にする. */
  private def toNode(
    n: js.Dynamic
  ): dom.Node =
    if (n.`type`.toString == "text") dom.document.createTextNode(n.value.toString)
    else {
      val el = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
      val cls = if (truthy(n.properties)) n.properties.className else js.undefined
      if (truthy(cls))
        el.className = if (js.Array.isArray(cls)) cls.asInstanceOf[js.Array[js.Any]].mkString(" ") else cls.toString
      n.children.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].foreach(_.foreach(c => el.appendChild(toNode(c))))
      el
    }

  private def highlightIn(
    root: dom.Document
  ): Unit = lib.foreach { hl =>
    val codes = root.querySelectorAll(".rich pre code:not([data-hl])")
    if (codes.length > 0) {
      if (!hl.ready().asInstanceOf[Boolean])
        hl.load().asInstanceOf[js.Promise[js.Any]].toFuture.foreach(_ => highlightIn(root))
      else codes.foreach { node =>
        val el = node.asInstanceOf[dom.HTMLElement]
        el.dataset("hl") = "1"
        val lang = Option(LangRe.exec(el.className)).flatMap(_(1).toOption)
        lang.foreach(l => Option(el.parentElement).foreach(_.dataset("lang") = Languages.labels.getOrElse(l, l)))
        val text = el.textContent
        try {
          val tree = hl.highlight(lang.orNull, text)
          el.asInstanceOf[js.Dynamic].replaceChildren(tree.children.asInstanceOf[js.Array[js.Dynamic]].toSeq.map(c =>
            toNode(c): js.Any
          )*)
          el.classList.add("hljs")
        } catch { case _: Throwable => () } // ハイライトできないときはそのまま
      }
    }
  }

  /** 起動時に1度呼ぶ. */
  def start(): Unit = {
    var queued = false
    def run(): Unit = { queued = false; highlightIn(dom.document) }
    new dom.MutationObserver(
      (
        _,
        _
      ) => if (!queued) { queued = true; dom.window.requestAnimationFrame(_ => run()) }
    )
      .observe(dom.document.body, new dom.MutationObserverInit { childList = true; subtree = true })
    run()
  }

}
