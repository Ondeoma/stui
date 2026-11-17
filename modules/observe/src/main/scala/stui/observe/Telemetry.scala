package stui.observe

import cats.effect.*
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.baggage.BaggageManager
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** 操作したユーザー（ログ・トレース・メトリクスに載せる）. */
final case class Actor(id: String, name: String)

/**
 * OpenTelemetry（otel4s-oteljava）. ログ・メトリクス・トレースを OTLP で skorimako などに送る.
 *
 * 送り先は OpenTelemetry の標準の環境変数で決める（`OTEL_EXPORTER_OTLP_ENDPOINT`・`OTEL_EXPORTER_OTLP_HEADERS` など）.
 * `OTEL_EXPORTER_OTLP_ENDPOINT` がなければ何も送らない（ログは標準出力だけ）.
 *
 * トレースの文脈は IOLocal に持ち（IOLocalContextStorage）、Java のライブラリ（logback）からも同じ文脈が見える.
 * そのため、ログにトレースの ID・操作したユーザーが付く.
 */
final class Telemetry private[observe] (val tracer: Tracer[IO], val meter: Meter[IO], val baggage: BaggageManager[IO], val enabled: Boolean)

object Telemetry:

  @volatile private var cur: Telemetry =
    new Telemetry(Tracer.noop[IO], Meter.noop[IO], BaggageManager.noop[IO], false)

  /** 起動時に作ったもの（作る前は何もしない）. */
  def current: Telemetry = cur

  /** 要求ごとの「操作したユーザー」を入れる箱（HTTP のミドルウェアが作り、authed が入れ、アクセスログが読む）. */
  private[observe] val actorSlot: IOLocal[Option[AtomicReference[Option[Actor]]]] =
    IOLocal[Option[AtomicReference[Option[Actor]]]](None).syncStep(100)
      .flatMap(_.leftMap(_ => new Error("IOLocal の初期化に失敗しました")).liftTo[SyncIO]).unsafeRunSync()

  /**
   * IOApp の main の最初に呼ぶ. IOLocal の文脈を Java のライブラリ（logback）からも見えるようにする.
   * （`-Dcats.effect.trackFiberContext=true` と同じ. 起動スクリプトに書かなくてよいよう、ここで設定する）
   */
  def prepare(): Unit = System.setProperty("cats.effect.trackFiberContext", "true")

  /**
   * 起動する. service はサービスの名前（`OTEL_SERVICE_NAME` があればそちら）、version はリソースの service.version.
   * 送り先がないときも作る（ログのユーザー・トレースの ID の仕組みは同じに動き、送らないだけ）.
   */
  def resource(service: String, version: String, runtime: IORuntime): Resource[IO, Telemetry] =
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]
    val env     = sys.env
    val enabled = env.get("OTEL_EXPORTER_OTLP_ENDPOINT").exists(_.trim.nonEmpty) && !env.get("OTEL_SDK_DISABLED").exists(_.equalsIgnoreCase("true"))
    // 環境変数（OTEL_*）が優先. ここはその既定値
    val defaults = Map(
      "otel.sdk.disabled"                -> (!enabled).toString,
      "otel.service.name"                -> service,
      "otel.resource.attributes"         -> s"service.version=$version,service.namespace=sbirds",
      "otel.exporter.otlp.protocol"      -> "http/protobuf",
      "otel.traces.exporter"             -> "otlp",
      "otel.metrics.exporter"            -> "otlp",
      "otel.logs.exporter"               -> "otlp",
      "otel.metric.export.interval"      -> "30000",
      "otel.traces.sampler"              -> "parentbased_traceidratio",
      "otel.traces.sampler.arg"          -> "1.0"
    )
    for
      otel   <- OtelJava.autoConfigured[IO](_.addPropertiesSupplier(() => defaults.asJava))
      _      <- Resource.eval(IO(OpenTelemetryAppender.install(otel.underlying)))
      _      <- Resource.fromAutoCloseable(IO(RuntimeTelemetry.create(otel.underlying)))
      _      <- {
                  given MeterProvider[IO] = otel.meterProvider
                  IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)
                }
      tracer <- Resource.eval(otel.tracerProvider.tracer(service).withVersion(version).get)
      meter  <- Resource.eval(otel.meterProvider.meter(service).withVersion(version).get)
      t       = new Telemetry(tracer, meter, otel.baggageManager, enabled)
      _      <- Resource.make(IO { cur = t })(_ => IO { cur = new Telemetry(Tracer.noop[IO], Meter.noop[IO], BaggageManager.noop[IO], false) })
      _      <- Resource.eval(Metrics.init(meter))
      _      <- Resource.eval(DbTelemetry.init)
      _      <- Resource.eval(IO(org.slf4j.LoggerFactory.getLogger("telemetry").info(
                  if enabled then s"OpenTelemetry: ${env.getOrElse("OTEL_EXPORTER_OTLP_ENDPOINT", "")} へ送ります（service.name=${env.getOrElse("OTEL_SERVICE_NAME", service)}）"
                  else "OpenTelemetry: OTEL_EXPORTER_OTLP_ENDPOINT が未設定のため送りません")))
    yield t

  /**
   * 操作したユーザーを、いまのスパンの属性（user.id・user.name）と、この中で出すログ（MDC の baggage.user.id・baggage.user.name）に載せる.
   * 各サービスの authed（ログインの確認）で、処理の本体をこれで囲む.
   */
  def withUser[A](id: String, name: String)(fa: IO[A]): IO[A] =
    val t = cur
    actorSlot.get.flatMap(_.traverse_(r => IO(r.set(Some(Actor(id, name)))))) *>
      t.tracer.currentSpanOrNoop.flatMap(_.addAttributes(List(Attribute("user.id", id), Attribute("user.name", name)))) *>
      t.baggage.local(_.updated("user.id", id).updated("user.name", name))(fa)

  /**
   * 処理をスパンで囲む（トレースの内訳に出る）. いまのスパンがあればその子、なければ新しいトレース（裏の処理の1件など）.
   * 例: `Telemetry.span("chat.post", "channel.id" -> id.toString)(io)`
   */
  def span[A](name: String, kv: (String, String)*)(fa: IO[A]): IO[A] =
    cur.tracer.spanBuilder(name).addAttributes(kv.map((k, v) => Attribute(k, v))).build.use { s =>
      fa.onError(e => s.recordException(e) *> s.setStatus(org.typelevel.otel4s.trace.StatusCode.Error, e.getClass.getSimpleName))
    }
