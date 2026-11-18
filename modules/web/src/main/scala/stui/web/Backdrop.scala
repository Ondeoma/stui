package stui.web

import org.scalajs.dom

/** モーダルの背景のクリックで閉じる処理.
  * 押し始めと離した所がどちらも背景のときだけ閉じる（中の入力欄で文字を選ぶドラッグが外で終わっても閉じない）.
  * 背景の要素に @mousedown="Backdrop.down(event)"・@mouseup="Backdrop.up(event)"・@click="Backdrop.click(event)(閉じる処理)" を付ける. */
object Backdrop {
  // 押し始めから離すまで、ずっと背景の上だったか
  private var onSelf = false

  private def self(e: dom.Event): Boolean = e.target == e.currentTarget

  def down(e: dom.Event): Unit = onSelf = self(e)

  def up(e: dom.Event): Unit = onSelf = onSelf && self(e)

  def click(e: dom.Event)(close: => Unit): Unit = {
    if (onSelf && self(e)) close
    onSelf = false
  }
}
