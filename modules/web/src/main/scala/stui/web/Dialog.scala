package stui.web

import io.github.ondeoma.scalactive.components.ComponentManager.*

import scala.scalajs.js

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(
    r: DialogReq
  ): Unit = current := Some(r)

  /** 確認（OK を押したら f）. */
  def confirm(
    message: String,
    ok: String = "OK",
    danger: Boolean = true
  )(
    f: => Unit
  ): Unit =
    open(DialogReq(message, ok, danger, None, Nil, cancel = true, _ => f, () => ()))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(
    message: String,
    default: String = "",
    ok: String = "OK"
  )(
    f: String => Unit
  ): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), Nil, cancel = true, f, () => ()))

  /** 選択肢から選ぶ（choices は 値 → 表示名）. */
  def choose(
    message: String,
    choices: List[(String, String)],
    ok: String = "OK",
    danger: Boolean = false
  )(
    f: String => Unit
  ): Unit =
    open(DialogReq(
      message,
      ok,
      danger,
      Some(RV(choices.headOption.map(_._1).getOrElse(""))),
      choices,
      cancel = true,
      f,
      () => ()
    ))

  def alert(
    message: String
  ): Unit = open(DialogReq(message, "OK", danger = false, None, Nil, cancel = false, _ => (), () => ()))

  def close(
    ok: Boolean
  ): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

  /** 文字の入力（エディタ用）. OK なら入力した値、キャンセルなら null の Promise. */
  def promptJs(
    message: String,
    default: String
  ): js.Promise[js.Any] =
    new js.Promise[js.Any](
      (
        resolve,
        _
      ) =>
        open(DialogReq(
          message,
          "OK",
          danger = false,
          Some(RV(Option(default).getOrElse(""))),
          Nil,
          cancel = true,
          v => resolve(v),
          () => resolve(null)
        ))
    )

}
