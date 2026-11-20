package stui.web

import io.github.ondeoma.scalactive.components.ComponentManager.Directive
import stui.shared.LinkServiceDto

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * v-html で描いた HTML（.rich）の後処理: コードブロックの色付けと、連携したサービスのリンクの名前（sbirds-lib の Rich）.
 * `<div class="rich" v-html="html" v-rich="html"></div>` のように付ける. 値は描き直しのきっかけ（v-html と同じ値を渡す）.
 */
val rich: Directive[Any] = sbirds.web.Rich.directive("stuiLowlight", Languages.labels)

/** 連携しているサービスを問い合わせて、そのリンクに名前を出し始める. */
def loadLinkTitles(): Future[List[LinkServiceDto]] = {
  val f = Api.get[List[LinkServiceDto]]("/api/integrations/links")
  f.foreach(_.foreach(l => sbirds.web.LinkTitles.start(l.provider, l.url, l.connected)))
  f
}
