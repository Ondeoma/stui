package stui.web

import org.scalajs.dom
import stui.shared.LinkServiceDto

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.timers.{SetTimeoutHandle, setTimeout}

/**
 * 外部サービス（skiwi・skawau・skea・skorimako）へのリンクに、リンク先の名前を出す（docs/integrations.md. 連携したサービスだけ動かす）.
 * 表示中の HTML（説明・コメント・リンクの一覧）からサービスの URL のリンクを探し、まとめて名前を問い合わせる.
 * 編集中のエディタ（contenteditable）の中は書き換えない.
 */
object LinkTitles {

  private val Uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

  // サービスごとの、URL の後ろの形（最後の () が問い合わせる ID）
  private val paths = Map(
    "skiwi" -> s"/w/$Uuid/p/($Uuid)", // ページ
    "skawau" -> s"/(?:view|office|files)/($Uuid)", // ファイル・フォルダ
    "skea" -> "/(?:p/[A-Za-z0-9]+/)?tasks/([A-Za-z][A-Za-z0-9]{1,9}-[0-9]+)", // タスク（STUI-12）
    "skorimako" -> s"/(traces/[0-9a-fA-F]{1,64}|(?:alerts|dashboards)/$Uuid)" // トレース・アラート・ダッシュボード（ID は traces/… の形）
  )

  // ID の大文字・小文字をそろえる（タスクIDは大文字、UUID は小文字）
  private def norm(provider: String, id: String): String = if (provider == "skea") id.toUpperCase else id.toLowerCase

  private def escape(s: String): String = s.replaceAll("""[.*+?^${}()|\[\]\\]""", """\\$0""")

  /** 1つのサービスのリンクを見張る. 状態は data-{provider}（pending・ok・none・off）に持つ. */
  private final class Watcher(provider: String, base: String, path: String) {

    private val re = js.RegExp("^" + escape(base) + path + "(?:[/?#]|$)")
    private val key = provider
    private var enabled = true
    private var timer: Option[SetTimeoutHandle] = None
    private val waiting = mutable.LinkedHashMap.empty[String, List[dom.HTMLAnchorElement]] // id -> リンク

    private def apply(a: dom.HTMLAnchorElement, item: Option[js.Dynamic]): Unit = item match {
      case None => a.dataset(key) = "none"
      case Some(it) =>
        a.dataset(key) = "ok"
        a.classList.add("ext-link")
        val title = it.title.toString
        a.title = s"$provider: $title"
        val icon = Option(it.icon).filterNot(js.isUndefined).map(_.toString).filter(_.nonEmpty).getOrElse("📄")
        val label = s"$icon $title"
        val text = Option(a.textContent).getOrElse("").trim
        // 文字が URL のままのリンクは名前に置き換え、文字を書いたリンクは名前を横に添える
        if (text.isEmpty || text == a.getAttribute("href") || text.startsWith(base)) a.textContent = label
        else if (Option(a.nextElementSibling).forall(!_.classList.contains("ext-title"))) {
          val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
          s.className = "ext-title"
          s.textContent = label
          a.asInstanceOf[js.Dynamic].after(s)
        }
    }

    private def clear(a: dom.Element): Unit = js.special.delete(a.asInstanceOf[dom.HTMLElement].dataset, key)

    private def schedule(ms: Int): Unit = timer = Some(setTimeout(ms.toDouble)(flush()))

    private def flush(): Unit = {
      timer = None
      val batch = waiting.take(100).toList
      batch.foreach((id, _) => waiting.remove(id))
      if (batch.nonEmpty) {
        val body = js.JSON.stringify(js.Dynamic.literal(ids = js.Array(batch.map(_._1)*)))
        dom.fetch(s"/api/integrations/$provider/titles", Api.init(dom.HttpMethod.POST, Some(body))).toFuture
          .flatMap(r => if (r.ok) r.json().toFuture else Future.failed(new Exception(r.status.toString)))
          .map { j =>
            val res = j.asInstanceOf[js.Dynamic]
            if (!js.DynamicImplicits.truthValue(res.connected)) {
              enabled = false
              batch.foreach((_, as) => as.foreach(_.dataset(key) = "off"))
            } else {
              val byId = res.items.asInstanceOf[js.Array[js.Dynamic]].map(p => norm(provider, p.id.toString) -> p).toMap
              batch.foreach((id, as) => as.foreach(apply(_, byId.get(id))))
            }
          }
          // 問い合わせできなかったリンクは、次に描き直されたときにもう一度試す
          .recover { case _ =>
            batch.foreach((_, as) => as.foreach(clear))
          }
          .foreach(_ => if (waiting.nonEmpty) schedule(50))
      }
    }

    private def scan(): Unit = if (enabled) {
      dom.document.querySelectorAll(s"a[href]:not([data-$key])").foreach { node =>
        val a = node.asInstanceOf[dom.HTMLAnchorElement]
        if (a.closest("[contenteditable=\"true\"]") == null) {
          Option(re.exec(Option(a.getAttribute("href")).getOrElse(""))).flatMap(_(1).toOption).foreach { m =>
            a.dataset(key) = "pending"
            val id = norm(provider, m)
            waiting(id) = waiting.getOrElse(id, Nil) :+ a
          }
        }
      }
      if (waiting.nonEmpty && timer.isEmpty) schedule(100)
    }

    private var queued = false
    new dom.MutationObserver((_, _) => if (!queued) { queued = true; setTimeout(30)({ queued = false; scan() }) })
      .observe(dom.document.body, new dom.MutationObserverInit { childList = true; subtree = true })
    scan()

    def reset(): Unit = {
      enabled = true
      dom.document.querySelectorAll(s"""a[data-$key="off"]""").foreach(clear)
      scan()
    }
  }

  private var watchers = Map.empty[String, Watcher]

  def start(svc: LinkServiceDto): Unit = if (svc.connected) watchers.get(svc.provider) match {
    case Some(w) => w.reset()
    case None => paths.get(svc.provider).foreach(p => watchers += svc.provider -> new Watcher(svc.provider, svc.url, p))
  }

  /** 連携しているサービスを問い合わせて動かす. */
  def load(): Future[List[LinkServiceDto]] = {
    val f = Api.get[List[LinkServiceDto]]("/api/integrations/links")
    f.foreach(_.foreach(start))
    f
  }

}
