package stui.db

import stui.core.ErrorCode

import cats.effect.*
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway

import scala.concurrent.duration.*

final case class DbConfig(
  url: String,
  user: String,
  password: String,
  maxPoolSize: Int = 10)

object Database {

  def migrate(
    cfg: DbConfig
  ): IO[Int] =
    IO.blocking {
      Flyway.configure().dataSource(cfg.url, cfg.user, cfg.password).load().migrate().migrationsExecuted
    }

  def transactor(
    cfg: DbConfig
  ): Resource[IO, HikariTransactor[IO]] = {
    val hc = new HikariConfig()
    hc.setJdbcUrl(cfg.url)
    hc.setUsername(cfg.user)
    hc.setPassword(cfg.password)
    hc.setMaximumPoolSize(cfg.maxPoolSize)
    hc.setDriverClassName("org.postgresql.Driver")
    // Patroni のフェイルオーバー対策: 接続待ちを短くし、切れた接続は早めに検出して張り直す。
    // URL は複数ホスト + targetServerType=primary を想定（例: jdbc:postgresql://db1,db2,db3/stui?targetServerType=primary）
    hc.setConnectionTimeout(5000)
    hc.setValidationTimeout(3000)
    hc.setKeepaliveTime(60000)
    hc.setMaxLifetime(600000)
    // SQL ごとにトレースの子のスパンと所要時間のメトリクスを残す（skorimako）
    HikariTransactor.fromHikariConfig[IO](hc, Some(sbirds.observe.DbTelemetry.logHandler))
  }
}

/** 定期的に動かす処理（キャンセルされるまで終わらない）。 */
object Periodic {

  /** initialDelay 待ってから action を動かし、以後 every ごとに繰り返す。失敗しても止めず、code を付けてログに出す。 */
  def run(
    initialDelay: FiniteDuration,
    every: FiniteDuration,
    code: ErrorCode
  )(
    action: IO[Any]
  ): IO[Nothing] =
    IO.sleep(initialDelay) *>
      (action.void.handleErrorWith(e => sbirds.observe.Log.report(code, e.toString, e)) *> IO.sleep(every)).foreverM
}

/** 送信待ちの表（メール・Webhook）の処理結果。 */
enum OutboxResult {
  case Sent
  case Skip(
    reason: String)
  case Retry(
    error: String)
}

object Outbox {

  /** 失敗 n 回目の再試行までの秒数（30秒から倍々、最大1時間）。 */
  def backoffSeconds(
    n: Int
  ): Int = math.min(3600, 30 * (1 << math.min(n, 7)))
}
