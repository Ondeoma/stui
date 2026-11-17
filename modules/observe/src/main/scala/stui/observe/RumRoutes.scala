package stui.observe

import cats.effect.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*

/**
 * 画面のブラウザの観測（skorimako の RUM）の設定. 画面（frontend/src/observe.js）が読み、設定があれば `{endpoint}/rum.js` を読み込む.
 *  - `SKORIMAKO_RUM_TOKEN`: skorimako の管理の画面で登録したアプリのトークン（`rum_…`. ブラウザに見えてよい値）
 *  - `SKORIMAKO_RUM_URL`: skorimako の公開 URL（省略すると `SKORIMAKO_URL`）
 *  - `SKORIMAKO_RUM_SAMPLE_RATE`: 送るセッションの割合（0〜1. 既定 1）
 * 設定がなければ 204（画面は何も読み込まない）.
 */
object RumRoutes:

  final case class RumConfig(endpoint: String, token: String, sampleRate: Double)

  def fromEnv: Option[RumConfig] =
    val env = sys.env.view.mapValues(_.trim).filter(_._2.nonEmpty).toMap
    for
      token <- env.get("SKORIMAKO_RUM_TOKEN")
      url   <- env.get("SKORIMAKO_RUM_URL").orElse(env.get("SKORIMAKO_URL"))
    yield RumConfig(url.stripSuffix("/"), token, env.get("SKORIMAKO_RUM_SAMPLE_RATE").flatMap(_.toDoubleOption).getOrElse(1.0))

  /** CSP の script-src・connect-src に足すオリジン. */
  def origin: Option[String] = fromEnv.flatMap(c => Uri.fromString(c.endpoint).toOption).map(u =>
    s"${u.scheme.fold("https")(_.value)}://${u.authority.fold("")(_.renderString)}")

  def routes: HttpRoutes[IO] =
    val cfg = fromEnv
    HttpRoutes.of[IO] { case GET -> Root / "api" / "observe" / "rum" =>
      cfg match
        case Some(c) => Ok(Json.obj("endpoint" -> Json.fromString(c.endpoint), "token" -> Json.fromString(c.token),
                          "sampleRate" -> Json.fromDoubleOrNull(c.sampleRate)))
        case None    => NoContent()
    }
