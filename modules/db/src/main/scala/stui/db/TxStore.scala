package stui.db

import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

/** 各メソッドを1トランザクションで実行するストアの共通部分。 */
abstract class TxStore(
  xa: Transactor[IO]) {
  protected def tx[A](
    c: ConnectionIO[A]
  ): IO[A] = c.transact(xa)

  /**
   * claim で取り出した行（SKIP LOCKED）を1件ずつ run で処理し、結果を record で記録する（送信待ちの表の処理）。
   * 取り出しから記録までを1つのトランザクションで行うので、処理中の行はほかのノードに取られない。
   */
  protected def processEach[A, R](
    claim: ConnectionIO[List[A]]
  )(
    run: A => IO[R]
  )(
    record: (A, R) => ConnectionIO[Int]
  ): IO[Int] =
    WeakAsync.liftK[IO, ConnectionIO].use(lift =>
      claim.flatMap(_.traverse(a => lift(run(a)).flatMap(record(a, _)))).map(_.size).transact(xa)
    )

  protected def fail[A](
    e: AppError
  ): ConnectionIO[Either[AppError, A]] = e.asLeft[A].pure[ConnectionIO]
}
