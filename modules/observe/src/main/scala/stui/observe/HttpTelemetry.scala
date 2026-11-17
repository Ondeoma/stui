package stui.observe

import cats.data.Kleisli
import cats.effect.*
import org.http4s.*
import org.typelevel.ci.CIString
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram}
import org.typelevel.otel4s.trace.{SpanKind, StatusCode}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * HTTP サーバーの観測:
 *  - 要求ごとのスパン（W3C traceparent を受け取れば、呼び出し元のトレースにつなぐ）. 属性は OpenTelemetry の HTTP の決まりに合わせる
 *  - 所要時間のヒストグラム `http.server.request.duration`（秒. メソッド・ルート・状態コード）
 *  - アクセスログ（ロガー `access`. メソッド・パス・状態・時間・操作したユーザー）
 * ルートは ID（UUID・数字・長いトークン）を `{id}` にまとめ、メトリクスの種類が増えすぎないようにする.
 */
object HttpTelemetry:

  private given TextMapGetter[Headers] with
    def get(carrier: Headers, key: String): Option[String] = carrier.get(CIString(key)).map(_.head.value)
    def keys(carrier: Headers): Iterable[String]           = carrier.headers.map(_.name.toString)

  private val accessLog = org.slf4j.LoggerFactory.getLogger("access")

  private val Uuid  = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r
  private val Num   = "^[0-9]+$".r
  private val Token = "^[A-Za-z0-9_\\-.~%]{24,}$".r

  /** /api/channels/0b3c…/messages → /api/channels/{id}/messages */
  def route(path: String): String =
    path.split('/').map { seg =>
      if Uuid.matches(seg) || Num.matches(seg) || Token.matches(seg) then "{id}" else seg
    }.mkString("/") match
      case "" => "/"
      case r  => r

  /** ログ・スパンに残すパス. 長いトークン（受信 Webhook の秘密の URL など）は伏せる（UUID・数字は残す） */
  def masked(path: String): String =
    path.split('/').map(seg => if !Uuid.matches(seg) && Token.matches(seg) then "***" else seg).mkString("/")

  private val buckets = BucketBoundaries(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10)

  /**
   * traced: スパン・メトリクス・アクセスログの対象にするパスの先頭（例: List("/api", "/auth")）.
   * それ以外（画面のファイル・/healthz）は何もしない.
   */
  def apply(app: HttpApp[IO], traced: List[String]): Resource[IO, HttpApp[IO]] =
    Resource.eval(Telemetry.current.meter.histogram[Double]("http.server.request.duration").withUnit("s")
      .withDescription("HTTP の要求の処理にかかった時間").withExplicitBucketBoundaries(buckets).create).map { hist =>
      Kleisli { (req: Request[IO]) =>
        val path = req.pathInfo.renderString
        if !traced.exists(p => path == p || path.startsWith(p + "/")) then app(req)
        else serve(app, req, path, hist)
      }
    }

  private def serve(app: HttpApp[IO], req: Request[IO], path: String, hist: Histogram[IO, Double]): IO[Response[IO]] =
    val t      = Telemetry.current
    val method = req.method.name
    val rt     = route(path)
    val shown  = masked(path)
    val base = List[Attribute[?]](
      Attribute("http.request.method", method), Attribute("url.path", shown), Attribute("http.route", rt), Attribute("url.scheme", req.uri.scheme.fold("http")(_.value.toString))
    ) ++ req.headers.get(CIString("User-Agent")).map(u => Attribute("user_agent.original", u.head.value.take(300))).toList ++
      clientIp(req).map(ip => Attribute("client.address", ip)).toList
    val slot = new AtomicReference[Option[Actor]](None)
    t.tracer.joinOrRoot(req.headers) {
      t.tracer.spanBuilder(s"$method $rt").withSpanKind(SpanKind.Server).addAttributes(base).build.use { span =>
        for
          start <- IO.monotonic
          res   <- Telemetry.actorSlot.getAndSet(Some(slot)).bracket(_ => app(req))(Telemetry.actorSlot.set).attempt
          end   <- IO.monotonic
          dur    = end - start
          status = res.fold(_ => 500, _.status.code)
          _ <- span.addAttributes(List(Attribute("http.response.status_code", status.toLong)))
          _ <- res.fold(e => span.recordException(e) *> span.setStatus(StatusCode.Error, e.getClass.getSimpleName),
                        r => IO.whenA(r.status.code >= 500)(span.setStatus(StatusCode.Error)))
          _ <- hist.record(dur.toNanos / 1e9, Attribute("http.request.method", method), Attribute("http.route", rt),
                 Attribute("http.response.status_code", status.toLong))
          _ <- IO(log(method, shown, status, dur, slot.get, res.left.toOption))
          r <- IO.fromEither(res)
        yield r
      }
    }

  private def clientIp(req: Request[IO]): Option[String] =
    req.headers.get(CIString("X-Forwarded-For")).map(_.head.value.split(',').head.trim).filter(_.nonEmpty)
      .orElse(req.remote.map(_.host.toString))

  private def log(method: String, path: String, status: Int, dur: FiniteDuration, actor: Option[Actor], err: Option[Throwable]): Unit =
    val ms = dur.toMillis
    val ev =
      if status >= 500 then accessLog.atWarn() else accessLog.atInfo()
    ev.addKeyValue("http.request.method", method).addKeyValue("url.path", path).addKeyValue("http.response.status_code", status)
      .addKeyValue("duration_ms", ms)
    actor.foreach(a => ev.addKeyValue("user.id", a.id).addKeyValue("user.name", a.name))
    err.foreach(ev.setCause)
    ev.log(s"$method $path $status ${ms}ms${actor.fold("")(a => s" user=${a.name}(${a.id})")}")
