package stui.web

import org.scalajs.dom

import scala.scalajs.js

/** editor.js の createEditor の戻り値. steps は [[コマンド名, ...引数], ...]（Tiptap の chain のコマンド）. */
@js.native
private trait EditorJs extends js.Object {
  def getJSON(): js.Any = js.native
  def setJSON(j: js.Any): Unit = js.native
  def isEmpty(): Boolean = js.native
  def clear(): Unit = js.native
  def focus(): Unit = js.native
  def insertText(t: String): Unit = js.native
  def destroy(): Unit = js.native
  def run(steps: js.Array[js.Array[js.Any]]): Unit = js.native
  def block(range: js.Any, steps: js.Array[js.Array[js.Any]]): Unit = js.native
  def editLink(): Unit = js.native
  def uploadFiles(files: js.Array[dom.File], pos: js.Any): Unit = js.native
  def isActive(tpe: String, attrs: js.Any): Boolean = js.native
  def attrs(tpe: String): js.Dynamic = js.native
  def isEditable(): Boolean = js.native
  def width(): Double = js.native
  def onUpdate(f: js.Function0[Unit]): Unit = js.native
}
