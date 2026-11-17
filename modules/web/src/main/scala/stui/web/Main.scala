package stui.web

import io.github.ondeoma.scalactive.ScalactiveConfig
import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

/**
 * 画面の入口.
 * `@main def main()` にすると生成される `main` クラスと `Main` が、大文字小文字を区別しないファイルシステム（macOS）で
 * 同じファイル名（main.sjsir / Main.sjsir）になり差分コンパイルが壊れるため、通常の main(args) にする.
 */
object Main {

  def main(args: Array[String]): Unit = {
    ScalactiveConfig.titleTemplate = "%s | stui"
    ScalactiveConfig.defaultTitle = Some("stui")
    Store.startSync()
    Theme.apply()
    // skorimako のブラウザの観測（設定があるときだけ）、.rich のコードのハイライト、PWA（ホーム画面に追加・オフラインで開く・プッシュ通知）
    Observe.start()
    Highlight.start()
    Pwa.register()
    App()(dom.document.body, AddMethod.appendS(IdSelector("app"))).init()
  }

}
