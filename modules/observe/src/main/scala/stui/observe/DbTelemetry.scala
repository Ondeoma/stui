package stui.observe

import cats.effect.*
import doobie.util.log.{ExecFailure, LogEvent, LogHandler, ProcessingFailure, Success}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram}
import org.typelevel.otel4s.trace.{SpanKind, StatusCode}


/**
 * DB（doobie）の観測. SQL を1つ実行するごとに:
 *  - いまのスパン（HTTP の要求・裏の処理）の子のスパン（例: `SELECT message`）. 実行と結果の読み出しにかかった時間
 *  - ヒストグラム `db.client.operation.duration`（秒. 操作（SELECT など）・表）
 * 親のスパンがないとき（定期的な読み込みなど）はスパンを作らない（トレースが細切れのもので埋まらないよう）.
 * 使い方: `HikariTransactor.fromHikariConfig[IO](hc, Some(DbTelemetry.logHandler))`
 */
object DbTelemetry:

  private val Op    = "(?is)^\\s*(?:with\\s.*?\\)\\s*)?(select|insert|update|delete|merge|with|create|alter|drop|truncate|copy|lock)\\b".r
  private val Table = "(?is)\\b(?:from|into|update|join)\\s+([a-z_][a-z0-9_.]*)".r
  private val buckets = BucketBoundaries(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5)

  @volatile private var hist: Option[Histogram[IO, Double]] = None

  private[observe] def init: IO[Unit] =
    Telemetry.current.meter.histogram[Double]("db.client.operation.duration").withUnit("s")
      .withDescription("SQL の実行にかかった時間").withExplicitBucketBoundaries(buckets).create.flatMap(h => IO { hist = Some(h) })

  /** SQL の種類と表（スパンの名前・メトリクスのラベル）. */
  def describe(sql: String): (String, Option[String]) =
    val op = Op.findFirstMatchIn(sql).map(_.group(1).toUpperCase).getOrElse("SQL")
    (op, Table.findFirstMatchIn(sql).map(_.group(1).toLowerCase))

  private def compact(sql: String): String = sql.replaceAll("\\s+", " ").trim.take(2000)

  val logHandler: LogHandler[IO] = new LogHandler[IO]:
    def run(e: LogEvent): IO[Unit] =
      val (dur, err) = e match
        case Success(_, _, _, exec, proc)              => (exec + proc, None)
        case ProcessingFailure(_, _, _, exec, proc, t) => (exec + proc, Some(t))
        case ExecFailure(_, _, _, exec, t)             => (exec, Some(t))
      val (op, table) = describe(e.sql)
      val name = table.fold(op)(t => s"$op $t")
      val attrs = List[Attribute[?]](Attribute("db.system.name", "postgresql"), Attribute("db.operation.name", op)) ++
        table.map(t => Attribute("db.collection.name", t)).toList
      val t = Telemetry.current
      val record = hist.fold(IO.unit)(_.record(dur.toNanos / 1e9, attrs*))
      val span = t.tracer.currentSpanContext.flatMap {
        case Some(parent) if parent.isValid =>
          for
            now <- IO.realTime
            s   <- t.tracer.spanBuilder(name).withSpanKind(SpanKind.Client)
                     .addAttributes(attrs ++ List(Attribute("db.query.text", compact(e.sql))) ++ Option(e.label).filter(l => l.nonEmpty && l != "unlabeled").map(l => Attribute("db.query.summary", l)).toList)
                     .modifyState(_.withStartTimestamp(now - dur)).build.startUnmanaged
            _   <- err.fold(IO.unit)(x => s.recordException(x) *> s.setStatus(StatusCode.Error, x.getClass.getSimpleName))
            _   <- s.end(now)
          yield ()
        case _ => IO.unit
      }
      (record *> span).handleError(_ => ())
