package stui.db

import cats.effect.*
import doobie.*
import doobie.implicits.*

import scala.concurrent.duration.*

/** 複数ノードのうち1つだけで動かす処理のリース（job_lock）。 */
final class JobLock(
  xa: Transactor[IO],
  holder: String) {

  /** name のリースを ttl の間取れたら true（同じノードなら延長）。 */
  def acquire(
    name: String,
    ttl: FiniteDuration
  ): IO[Boolean] = {
    val secs = ttl.toSeconds.toInt
    sql"""insert into job_lock (name, holder, expires_at) values ($name, $holder, now() + make_interval(secs => $secs))
          on conflict (name) do update set holder = excluded.holder, expires_at = excluded.expires_at
          where job_lock.holder = excluded.holder or job_lock.expires_at < now()
          returning holder""".query[String].option.transact(xa).map(_.contains(holder))
  }

  /** リースを取れたときだけ action を動かす。 */
  def exclusive(
    name: String,
    ttl: FiniteDuration
  )(
    action: IO[Unit]
  ): IO[Unit] =
    acquire(name, ttl).ifM(action, IO.unit)
}
