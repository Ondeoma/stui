package stui.web

import io.github.ondeoma.scalactive.ScalactiveConfig
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import io.github.ondeoma.scalactive.routes.Router
import io.github.ondeoma.scalactive.utils.TypeAlias.PathPatternRoutes

/** 存在しない URL（画面のどのルートにも当たらないパス）はトップへ移す（履歴は置き換える）. */
object Fallback {

  def install(routes: PathPatternRoutes): Unit = {
    val patterns = RouterComponent.toURLPatterns(routes, ScalactiveConfig.fixedBasePath).map(_._1)
    Router.beforeEach((to, _) => if (patterns.exists(_.test(to.href))) Router.Continue else Router.Redirect("/"))
  }

}
