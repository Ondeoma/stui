package stui.web

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/**
 * PWA: Service Worker（frontend/public/sw.js）の登録と、プッシュ通知の購読（この端末で受け取る）.
 * 設定の画面の部品 PushToggle から使う. sw.js は画面とは別に動くスクリプトなので JS のまま.
 */
object Pwa {

  private def nav: js.Dynamic = dom.window.navigator.asInstanceOf[js.Dynamic]
  // 無いブラウザもあるので window のプロパティとして読む（js.Dynamic.global だと ReferenceError になる）
  private def win: js.Dynamic = dom.window.asInstanceOf[js.Dynamic]
  private def supportsSw: Boolean = !js.isUndefined(nav.serviceWorker)
  private def supportsPush: Boolean =
    supportsSw && !js.isUndefined(win.PushManager) && !js.isUndefined(win.Notification)

  private def isLocalDev: Boolean = Set("localhost", "127.0.0.1").contains(dom.window.location.hostname)

  /** 起動時に呼ぶ. 開発中（localhost）は localStorage の sw.dev=1 のときだけ（キャッシュで画面の変更が見えなくならないよう）. */
  def register(): Unit =
    if (supportsSw && (!isLocalDev || scala.util.Try(dom.window.localStorage.getItem("sw.dev")).toOption.contains("1")))
      dom.window.addEventListener("load", (_: dom.Event) => { nav.serviceWorker.register("/sw.js").asInstanceOf[js.Promise[js.Any]].toFuture.failed.foreach(_ => ()) })

  private val isIos = js.RegExp("iPhone|iPad|iPod").test(dom.window.navigator.userAgent)
  private def standalone: Boolean =
    dom.window.matchMedia("(display-mode: standalone)").matches || nav.standalone.asInstanceOf[js.UndefOr[Boolean]].contains(true)

  private def bytes(b64: String): Uint8Array = {
    val s = dom.window.atob(b64.replace('-', '+').replace('_', '/') + "=" * ((4 - b64.length % 4) % 4))
    val a = new Uint8Array(s.length)
    s.indices.foreach(i => a(i) = s.charAt(i).toShort)
    a
  }

  private def sameKey(sub: js.Dynamic, key: Uint8Array): Boolean = {
    val k = sub.options.applicationServerKey
    if (js.isUndefined(k) || k == null) true
    else {
      val a = new Uint8Array(k.asInstanceOf[scala.scalajs.js.typedarray.ArrayBuffer])
      a.length == key.length && (0 until a.length).forall(i => a(i) == key(i))
    }
  }

  private def subscription: Future[Option[js.Dynamic]] =
    nav.serviceWorker.getRegistration().asInstanceOf[js.Promise[js.UndefOr[js.Dynamic]]].toFuture.flatMap { reg =>
      reg.toOption match {
        case None    => Future.successful(None)
        case Some(r) => r.pushManager.getSubscription().asInstanceOf[js.Promise[js.Dynamic]].toFuture.map(s => Option(s).filterNot(js.isUndefined))
      }
    }

  /** unsupported（使えない）・install（iPhone・iPad でホーム画面に追加すれば使える）・denied（ブラウザで拒否）・on・off. */
  def state(): Future[String] =
    if (!supportsPush) Future.successful(if (isIos && !standalone) "install" else "unsupported")
    else if (win.Notification.permission.toString == "denied") Future.successful("denied")
    else subscription.map(s => if (s.isDefined) "on" else "off")

  def enable(): Future[String] =
    win.Notification.requestPermission().asInstanceOf[js.Promise[String]].toFuture.flatMap { perm =>
      if (perm != "granted") state()
      else for {
        reg  <- nav.serviceWorker.register("/sw.js").asInstanceOf[js.Promise[js.Dynamic]].toFuture
        _    <- nav.serviceWorker.ready.asInstanceOf[js.Promise[js.Any]].toFuture
        cfg  <- Api.get[io.circe.Json]("/api/push")
        key   = bytes(cfg.hcursor.get[String]("publicKey").getOrElse(""))
        old  <- reg.pushManager.getSubscription().asInstanceOf[js.Promise[js.Dynamic]].toFuture
        // サーバーの鍵が変わっていたら購読し直す
        kept <- if (old != null && !js.isUndefined(old) && !sameKey(old, key)) old.unsubscribe().asInstanceOf[js.Promise[js.Any]].toFuture.map(_ => None)
                else Future.successful(Option(old).filterNot(js.isUndefined))
        sub  <- kept.fold(reg.pushManager.subscribe(js.Dynamic.literal(userVisibleOnly = true, applicationServerKey = key)).asInstanceOf[js.Promise[js.Dynamic]].toFuture)(Future.successful)
        res  <- dom.fetch("/api/push/subscriptions", Api.init(dom.HttpMethod.POST, Some(js.JSON.stringify(sub.toJSON())))).toFuture
      } yield if (res.ok) "on" else throw new Exception("この端末を登録できませんでした")
    }

  def disable(): Future[String] = subscription.flatMap {
    case None => Future.successful("off")
    case Some(sub) =>
      dom.fetch("/api/push/subscriptions", Api.init(dom.HttpMethod.DELETE, Some(js.JSON.stringify(js.Dynamic.literal(endpoint = sub.endpoint))))).toFuture.recover { case _ => null }
        .flatMap(_ => sub.unsubscribe().asInstanceOf[js.Promise[js.Any]].toFuture).map(_ => "off")
  }

}
