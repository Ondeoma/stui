package stui.web

import org.scalajs.dom

import scala.scalajs.js

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  /** 押した要素の下に、スクロールする枠（なければ画面）の端までどれだけ余白があるか（px）. メニューを上下どちらに開くかに使う. */
  def spaceBelow(e: dom.Event): Double = {
    val el = e.currentTarget.asInstanceOf[dom.HTMLElement]
    val bottom = el.getBoundingClientRect().bottom
    def scroller(n: dom.HTMLElement): Option[dom.HTMLElement] =
      if (n == null) None
      else {
        val oy = dom.window.getComputedStyle(n).overflowY
        if (oy == "auto" || oy == "scroll") Some(n) else scroller(n.parentElement)
      }
    val limit =
      scroller(el.parentElement).map(_.getBoundingClientRect().bottom).getOrElse(dom.window.innerHeight.toDouble)
    limit - bottom
  }

  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

}
