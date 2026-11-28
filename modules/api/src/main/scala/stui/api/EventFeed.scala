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
final class EventFeed private (messages: MessageStore,
                               activity: ActivityStore,
                               subs: Ref[IO, Map[Long, Queue[IO, FeedItem]]],
                               perUser: Ref[IO, Map[UUID, Int]],
                               ids: Ref[IO, Long]) {

  private val MaxPerUser = 20

  private def publish(item: FeedItem): IO[Unit] = subs.get.flatMap(_.values.toList.traverse_(_.tryOffer(item)))

  def loop: IO[Nothing] =
    (messages.latestEvent, IO.realTimeInstant).tupled.flatMap {
      (start, now) =>
        def step(last: Long, since: Instant): IO[(Long, Instant)] =
          for {
            evs <- messages.eventsAfter(last)
            _ <- evs.traverse_((id, c, u) => publish(FeedItem.Changed(c, id, u)))
            ns <- activity.notifiedSince(since)
            _ <- ns.traverse_((u, _) => publish(FeedItem.Notified(u)))
          } yield (evs.lastOption.fold(last)(_._1), (since :: ns.map(_._2)).max)
        def go(last: Long, since: Instant): IO[Nothing] =
          IO.sleep(500.millis) *> step(last, since).handleErrorWith(e =>
            sbirds.observe.Log.report(ErrorCode.EventFeedFailed, e.toString, e).as((last, since))
          )
            .flatMap((l, s) => go(l, s))
        go(start, now)
    }

  /**
   * ボイスチャンネルの取り決め（SDP・ICE の候補）を配る。0.15秒ごとに最近15秒の行を読み、まだ配っていないものを宛先の人の画面へ送る
   * （番号は書き込みの確定の順と前後することがあるため、番号で区切らず、配った番号を30秒覚えておく）。
   */
  def signalLoop(voice: VoiceStore): IO[Nothing] = {
    def go(seen: Map[Long, Long]): IO[Nothing] =
      IO.sleep(150.millis) *> (voice.recentSignals(15), IO.monotonic.map(_.toMillis)).tupled.flatMap { (rows, now) =>
        val fresh = rows.filterNot(r => seen.contains(r.id))
        fresh.traverse_(r => publish(FeedItem.Signal(r.toUser, VoiceSignals.json(r)))).as(
          (seen ++ fresh.map(_.id -> now)).filter(_._2 > now - 30000)
        )
      }.handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.VoiceSignalFeedFailed, e.toString, e).as(seen))
        .flatMap(go)
    // 起動前の行は配らない
    (voice.recentSignals(30), IO.monotonic.map(_.toMillis)).tupled
      .handleError(_ => (Nil, 0L))
      .flatMap((rows, now) => go(rows.map(_.id -> now).toMap))
  }

  /** 購読する。接続が多すぎれば None。 */
  def subscribe(user: UUID): IO[Option[Stream[IO, FeedItem]]] =
    perUser.modify { m =>
      val n = m.getOrElse(user, 0)
      if n >= MaxPerUser then (m, false) else (m.updated(user, n + 1), true)
    }.flatMap {
      case false => IO.pure(None)
      case true =>
        val release = perUser.update(m => m.get(user).fold(m)(n => if n <= 1 then m - user else m.updated(user, n - 1)))
        Some(Stream.bracket(
          (Queue.dropping[IO, FeedItem](1024), ids.getAndUpdate(_ + 1)).tupled.flatTap((q, id) =>
            subs.update(_ + (id -> q))
          )
        )((_, id) => subs.update(_ - id) *> release).flatMap((q, _) => Stream.fromQueueUnterminated(q))).pure[IO]
    }
}

object EventFeed {
  def apply(messages: MessageStore, activity: ActivityStore): IO[EventFeed] =
    (Ref.of[IO, Map[Long, Queue[IO, FeedItem]]](Map.empty), Ref.of[IO, Map[UUID, Int]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new EventFeed(messages, activity, _, _, _))
}

/** 届ける取り決めの JSON（SSE の voice-signal）。 */
object VoiceSignals {
  def json(r: VoiceSignalRow): String = {
    import io.circe.syntax.*
    stui.shared.VoiceSignalDto(
      r.id,
      r.toConn,
      r.fromConn,
      io.circe.parser.parse(r.data).getOrElse(io.circe.Json.Null)
    ).asJson.noSpaces
  }
}
