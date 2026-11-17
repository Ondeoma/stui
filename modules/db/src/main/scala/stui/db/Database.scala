package stui.db

import cats.effect.*
import cats.syntax.all.*
import com.zaxxer.hikari.HikariConfig
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import org.flywaydb.core.Flyway

import scala.concurrent.duration.*

final case class DbConfig(url: String, user: String, password: String, maxPoolSize: Int = 10)

object Database:

  def migrate(cfg: DbConfig): IO[Int] =
    IO.blocking {
      Flyway.configure().dataSource(cfg.url, cfg.user, cfg.password).load().migrate().migrationsExecuted
    }

  def transactor(cfg: DbConfig): Resource[IO, HikariTransactor[IO]] =
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
    HikariTransactor.fromHikariConfig[IO](hc)

enum StoreError:
  case NotFound(what: String)
  case Invalid(msg: String)
  case Conflict(msg: String)

  /** 利用者に返す文言 */
  def message: String = this match
    case NotFound(w) => s"$w が見つかりません"
    case Invalid(m)  => m
    case Conflict(m) => m

/** 各メソッドを1トランザクションで実行するストアの共通部分。 */
abstract class TxStore(xa: Transactor[IO]):
  protected def tx[A](c: ConnectionIO[A]): IO[A] = c.transact(xa)

  /** claim で取り出した行（SKIP LOCKED）を1件ずつ run で処理し、結果を record で記録する（送信待ちの表の処理）。
    * 取り出しから記録までを1つのトランザクションで行うので、処理中の行はほかのノードに取られない。 */
  protected def processEach[A, R](claim: ConnectionIO[List[A]])(run: A => IO[R])(record: (A, R) => ConnectionIO[Int]): IO[Int] =
    WeakAsync.liftK[IO, ConnectionIO].use(lift => claim.flatMap(_.traverse(a => lift(run(a)).flatMap(record(a, _)))).map(_.size).transact(xa))

  protected def fail[A](e: StoreError): ConnectionIO[Either[StoreError, A]] = e.asLeft[A].pure[ConnectionIO]

/** 定期的に動かす処理（キャンセルされるまで終わらない）。 */
object Periodic:
  /** initialDelay 待ってから action を動かし、以後 every ごとに繰り返す。失敗しても止めず、onError の文を出力する。 */
  def run(initialDelay: FiniteDuration, every: FiniteDuration)(onError: Throwable => String)(action: IO[Any]): IO[Nothing] =
    IO.sleep(initialDelay) *> (action.void.handleErrorWith(e => IO.println(onError(e))) *> IO.sleep(every)).foreverM

/** 複数ノードのうち1つだけで動かす処理のリース（job_lock）。 */
final class JobLock(xa: Transactor[IO], holder: String):

  /** name のリースを ttl の間取れたら true（同じノードなら延長）。 */
  def acquire(name: String, ttl: FiniteDuration): IO[Boolean] =
    val secs = ttl.toSeconds.toInt
    sql"""insert into job_lock (name, holder, expires_at) values ($name, $holder, now() + make_interval(secs => $secs))
          on conflict (name) do update set holder = excluded.holder, expires_at = excluded.expires_at
          where job_lock.holder = excluded.holder or job_lock.expires_at < now()
          returning holder""".query[String].option.transact(xa).map(_.contains(holder))

  /** リースを取れたときだけ action を動かす。 */
  def exclusive(name: String, ttl: FiniteDuration)(action: IO[Unit]): IO[Unit] =
    acquire(name, ttl).ifM(action, IO.unit)

/** 送信待ちの表（メール・Webhook）の処理結果。 */
enum OutboxResult:
  case Sent
  case Skip(reason: String)
  case Retry(error: String)

object Outbox:
  /** 失敗 n 回目の再試行までの秒数（30秒から倍々、最大1時間）。 */
  def backoffSeconds(n: Int): Int = math.min(3600, 30 * (1 << math.min(n, 7)))

/** SQL の部品（doobie の Fragments を List で使うため）。 */
object Sql:
  /** set a = 1, b = 2（空のリストは渡さないこと）。 */
  def set(fs: List[Fragment]): Fragment = fr"set" ++ fs.reduce(_ ++ fr"," ++ _)
  /** where a and b（空なら何も付けない）。 */
  def whereAnd(fs: List[Fragment]): Fragment =
    if fs.isEmpty then Fragment.empty else fr"where" ++ fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def and(fs: List[Fragment]): Fragment =
    if fs.isEmpty then fr"true" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"and" ++ _)
  def or(fs: List[Fragment]): Fragment =
    if fs.isEmpty then fr"false" else fs.map(f => fr"(" ++ f ++ fr")").reduce(_ ++ fr"or" ++ _)
  def commas(fs: List[Fragment]): Fragment = fs.reduce(_ ++ fr"," ++ _)
