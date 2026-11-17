package stui.web

import org.scalajs.dom

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** 画像の加工. */
object Images {

  /** 画像を中央で正方形に切り取り、size px に縮める（アイコンの登録用）. GIF などで読めないときは元のファイルを返す. */
  def squareThumb(file: dom.File, size: Int = 256): Future[dom.File] = {
    val p = Promise[dom.File]()
    val url = dom.URL.createObjectURL(file)
    val img = dom.document.createElement("img").asInstanceOf[dom.HTMLImageElement]
    img.onload = (_: dom.Event) => {
      val (w, h) = (img.naturalWidth, img.naturalHeight)
      val s = math.min(w, h)
      val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
      canvas.width = math.min(size, s)
      canvas.height = canvas.width
      canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
        .drawImage(img, (w - s) / 2.0, (h - s) / 2.0, s, s, 0, 0, canvas.width, canvas.height)
      dom.URL.revokeObjectURL(url)
      val done: js.Function1[dom.Blob, Unit] = b =>
        p.success(if (b == null) file else new dom.File(js.Array(b), "avatar.png", new dom.FilePropertyBag { `type` = "image/png" }))
      canvas.asInstanceOf[js.Dynamic].toBlob(done, "image/png")
    }
    img.addEventListener("error", (_: dom.Event) => { dom.URL.revokeObjectURL(url); p.success(file) })
    img.src = url
    p.future
  }

}
