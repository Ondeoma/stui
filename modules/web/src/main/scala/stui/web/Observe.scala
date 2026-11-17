package stui.web

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * skorimako のブラウザの観測（RUM）. サーバーに設定（SKORIMAKO_RUM_TOKEN）があれば `{endpoint}/rum.js` を読み込む.
 * 表示の速さ（Web Vitals）・JavaScript のエラー・API の失敗が skorimako に届く. ログインした人は setUser で知らせる.
 * （rum.js は skorimako が配る SDK なので JS のまま. 読み込みと利用者の紐付けだけここで行う）
 */
object Observe {

  private var user: Option[String] = None

  // 読み込む前は無い（js.Dynamic.global で読むと ReferenceError になるので window のプロパティとして読む）
  private def sdk: js.UndefOr[js.Dynamic] = dom.window.asInstanceOf[js.Dynamic].skorimako.asInstanceOf[js.UndefOr[js.Dynamic]]

  /** 画面の起動時に1度呼ぶ. 設定がなければ（204）何もしない. */
  def start(): Unit = {
    dom.fetch("/api/observe/rum", Api.init(dom.HttpMethod.GET)).toFuture
      .flatMap(r => if (r.status == 200) r.json().toFuture.map(j => Option(j.asInstanceOf[js.Dynamic])) else Future.successful(None))
      .foreach {
        case Some(c) if !js.isUndefined(c.token) && !js.isUndefined(c.endpoint) =>
          val s = dom.document.createElement("script").asInstanceOf[dom.HTMLScriptElement]
          s.src = s"${c.endpoint}/rum.js"
          s.defer = true
          s.setAttribute("data-token", c.token.toString)
          s.setAttribute("data-endpoint", c.endpoint.toString)
          if (!js.isUndefined(c.sampleRate) && c.sampleRate != null) s.setAttribute("data-sample-rate", c.sampleRate.toString)
          s.onload = (_: dom.Event) => user.foreach(push)
          dom.document.head.appendChild(s)
        case _ => ()
      }
  }

  /** ログインした人（ユーザーの ID）. /api/me を読んだ後に呼ぶ. */
  def setUser(id: String): Unit = { user = Some(id); push(id) }

  private def push(id: String): Unit = sdk.foreach(_.setUser(id))

}
