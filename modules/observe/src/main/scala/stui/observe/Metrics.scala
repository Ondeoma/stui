package stui.observe

import cats.effect.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Measurement, Meter, UpDownCounter}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

/**
 * サービスの業務のメトリクス（投稿の数・ログインの数・接続の数など）. どこからでも呼べるよう、名前で計器を引く.
 * 起動前（Telemetry.resource の前）・送り先がないときは何もしない.
 *
 * 名前は `<サービス>.<対象>.<動き>`（例: stui.messages.posted）. skorimako では `stui_messages_posted` になる.
 */
object Metrics:

  private val meterRef  = Ref.unsafe[IO, Meter[IO]](Meter.noop[IO])
  private val counters  = Ref.unsafe[IO, Map[String, Counter[IO, Long]]](Map.empty)
  private val upDowns   = Ref.unsafe[IO, Map[String, UpDownCounter[IO, Long]]](Map.empty)

  private[observe] def init(m: Meter[IO]): IO[Unit] = meterRef.set(m) *> counters.set(Map.empty) *> upDowns.set(Map.empty)

  private def attrs(kv: Seq[(String, String)]): List[Attribute[String]] = kv.toList.map((k, v) => Attribute(k, v))

  /** 数える（例: Metrics.count("stui.messages.posted", "kind" -> "reply")）. */
  def count(name: String, kv: (String, String)*): IO[Unit] = add(name, 1L, kv*)

  def add(name: String, n: Long, kv: (String, String)*): IO[Unit] =
    counters.get.map(_.get(name)).flatMap {
      case Some(c) => IO.pure(c)
      case None    => meterRef.get.flatMap(_.counter[Long](name).create).flatTap(c => counters.update(_.updated(name, c)))
    }.flatMap(_.add(n, attrs(kv)*)).handleError(_ => ())

  /** 増えたり減ったりする数（接続中の数など）. */
  def upDown(name: String, n: Long, kv: (String, String)*): IO[Unit] =
    upDowns.get.map(_.get(name)).flatMap {
      case Some(c) => IO.pure(c)
      case None    => meterRef.get.flatMap(_.upDownCounter[Long](name).create).flatTap(c => upDowns.update(_.updated(name, c)))
    }.flatMap(_.add(n, attrs(kv)*)).handleError(_ => ())

  /** 処理の間だけ数を1つ増やす（SSE の接続など）. */
  def tracking[A](name: String, kv: (String, String)*)(fa: IO[A]): IO[A] =
    IO.uncancelable(poll => upDown(name, 1, kv*) *> poll(fa).guarantee(upDown(name, -1, kv*)))

  /**
   * DB などから定期的に読む値（ユーザーの数など）. every ごとに query を実行して覚え、メトリクスを送るときにその値を出す.
   * query が失敗したときは前の値のまま.
   */
  def gauge(name: String, description: String, every: FiniteDuration = 1.minute)(query: IO[Long]): Resource[IO, Unit] =
    val last = new AtomicLong(-1L)
    val refresh = query.flatMap(v => IO(last.set(v))).handleError(_ => ())
    for
      m <- Resource.eval(meterRef.get)
      _ <- (refresh *> IO.sleep(every)).foreverM.background
      _ <- m.observableGauge[Long](name).withDescription(description)
             .create(IO(Option(last.get).filter(_ >= 0).map(v => Measurement(v)).toList))
    yield ()
