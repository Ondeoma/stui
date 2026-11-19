package stui.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import cats.effect.std.Queue
import stui.core.*
import stui.db.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * 変更の配信（SSE）。各ノードが0.5秒ごとに新しい変更履歴と通知を DB で確かめ、そのノードにつないでいる画面へ知らせる
 * （ノードどうしの連絡は不要。DB がどのノードの変更も持っているため）。
 * 接続ごとに上限付きのキューを持ち、あふれたら捨てる（受け取らない接続があっても、ほかの人への配信を止めない。
 * 画面は次の知らせで /api/changes から追いつく）。ユーザーごとの接続数にも上限がある。
 */
final class EventFeed private (
  messages: MessageStore,
  activity: ActivityStore,
  subs: Ref[IO, Map[Long, Queue[IO, FeedItem]]],
  perUser: Ref[IO, Map[UUID, Int]],
  ids: Ref[IO, Long]) {

  private val MaxPerUser = 20

  private def publish(
    item: FeedItem
  ): IO[Unit] = subs.get.flatMap(_.values.toList.traverse_(_.tryOffer(item)))

  def loop: IO[Nothing] =
    (messages.latestEvent, IO.realTimeInstant).tupled.flatMap {
      (
        start,
        now
      ) =>
        def step(
          last: Long,
          since: Instant
        ): IO[(Long, Instant)] =
          for {
            evs <- messages.eventsAfter(last)
            _ <- evs.traverse_(
              (
                id,
                c,
                u
              ) => publish(FeedItem.Changed(c, id, u))
            )
            ns <- activity.notifiedSince(since)
            _ <- ns.traverse_(
              (
                u,
                _
              ) => publish(FeedItem.Notified(u))
            )
          } yield (evs.lastOption.fold(last)(_._1), (since :: ns.map(_._2)).max)
        def go(
          last: Long,
          since: Instant
        ): IO[Nothing] =
          IO.sleep(500.millis) *> step(last, since).handleErrorWith(e =>
            sbirds.observe.Log.report(ErrorCode.EventFeedFailed, e.toString, e).as((last, since))
          )
            .flatMap(
              (
                l,
                s
              ) => go(l, s)
            )
        go(start, now)
    }

  /** 購読する。接続が多すぎれば None。 */
  def subscribe(
    user: UUID
  ): IO[Option[Stream[IO, FeedItem]]] =
    perUser.modify { m =>
      val n = m.getOrElse(user, 0)
      if n >= MaxPerUser then (m, false) else (m.updated(user, n + 1), true)
    }.flatMap {
      case false => IO.pure(None)
      case true =>
        val release = perUser.update(m => m.get(user).fold(m)(n => if n <= 1 then m - user else m.updated(user, n - 1)))
        Some(Stream.bracket(
          (Queue.dropping[IO, FeedItem](1024), ids.getAndUpdate(_ + 1)).tupled.flatTap(
            (
              q,
              id
            ) => subs.update(_ + (id -> q))
          )
        )(
          (
            _,
            id
          ) => subs.update(_ - id) *> release
        ).flatMap(
          (
            q,
            _
          ) => Stream.fromQueueUnterminated(q)
        )).pure[IO]
    }
}

object EventFeed {
  def apply(
    messages: MessageStore,
    activity: ActivityStore
  ): IO[EventFeed] =
    (Ref.of[IO, Map[Long, Queue[IO, FeedItem]]](Map.empty), Ref.of[IO, Map[UUID, Int]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new EventFeed(messages, activity, _, _, _))
}
