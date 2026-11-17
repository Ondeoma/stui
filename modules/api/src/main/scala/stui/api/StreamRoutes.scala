package stui.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import cats.effect.std.Queue
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import stui.core.*
import stui.db.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（チャンネルの変更、ユーザーへの通知）。 */
enum FeedItem:
  case Changed(channelId: UUID, eventId: Long, userId: Option[UUID])
  case Notified(userId: UUID)

/**
 * 変更の配信（SSE）。各ノードが0.5秒ごとに新しい変更履歴と通知を DB で確かめ、そのノードにつないでいる画面へ知らせる
 * （ノードどうしの連絡は不要。DB がどのノードの変更も持っているため）。
 * 接続ごとに上限付きのキューを持ち、あふれたら捨てる（受け取らない接続があっても、ほかの人への配信を止めない。
 * 画面は次の知らせで /api/changes から追いつく）。ユーザーごとの接続数にも上限がある。
 */
final class EventFeed private (messages: MessageStore, activity: ActivityStore,
    subs: Ref[IO, Map[Long, Queue[IO, FeedItem]]], perUser: Ref[IO, Map[UUID, Int]], ids: Ref[IO, Long]):

  private val MaxPerUser = 20

  private def publish(item: FeedItem): IO[Unit] = subs.get.flatMap(_.values.toList.traverse_(_.tryOffer(item)))

  def loop: IO[Nothing] =
    (messages.latestEvent, IO.realTimeInstant).tupled.flatMap { (start, now) =>
      def step(last: Long, since: Instant): IO[(Long, Instant)] =
        for
          evs <- messages.eventsAfter(last)
          _   <- evs.traverse_((id, c, u) => publish(FeedItem.Changed(c, id, u)))
          ns  <- activity.notifiedSince(since)
          _   <- ns.traverse_((u, _) => publish(FeedItem.Notified(u)))
        yield (evs.lastOption.fold(last)(_._1), (since :: ns.map(_._2)).max)
      def go(last: Long, since: Instant): IO[Nothing] =
        IO.sleep(500.millis) *> step(last, since).handleErrorWith(e => IO.println(s"[stui] event feed failed: $e").as((last, since)))
          .flatMap((l, s) => go(l, s))
      go(start, now)
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
          (Queue.dropping[IO, FeedItem](1024), ids.getAndUpdate(_ + 1)).tupled.flatTap((q, id) => subs.update(_ + (id -> q))))(
          (_, id) => subs.update(_ - id) *> release
        ).flatMap((q, _) => Stream.fromQueueUnterminated(q))).pure[IO]
    }

object EventFeed:
  def apply(messages: MessageStore, activity: ActivityStore): IO[EventFeed] =
    (Ref.of[IO, Map[Long, Queue[IO, FeedItem]]](Map.empty), Ref.of[IO, Map[UUID, Int]](Map.empty), Ref.of[IO, Long](0L))
      .mapN(new EventFeed(messages, activity, _, _, _))

final class StreamRoutes(feed: EventFeed, channels: ChannelStore, val sessions: SessionAuth, val access: Access2) extends RouteSupport:

  private def sse(kind: String, data: Json): ServerSentEvent = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    /**
     * 画面への知らせ。参加しているチャンネル（と watch で指定した見ている公開チャンネル）に変更があれば change、
     * 自分への通知があれば notify を送る。届いたら画面が /api/changes で中身を取りに行く。
     */
    case req @ GET -> Root / "api" / "stream" =>
      authed(req) { s =>
        val watch = param(req, "watch").flatMap(uuidOpt)
        watch.fold(IO.pure(Right(()): Either[Fail, Unit]))(c => access.channel(s, c, Need.Read).map(_.void)).flatMap {
          case Left(f) => fail(f)
          case _ =>
            feed.subscribe(s.user.id).flatMap {
            case None => err(Status.TooManyRequests, "同時に開いている画面が多すぎます")
            case Some(feedItems) =>
            channels.memberChannelIds(s.user.id).flatMap(Ref.of[IO, Set[UUID]](_)).flatMap { member =>
              val events = feedItems.evalMapFilter {
                case FeedItem.Changed(c, id, u) =>
                  // 自分が参加・退出したら、参加しているチャンネルを読み直す
                  val refresh = if u.contains(s.user.id) then channels.memberChannelIds(s.user.id).flatMap(member.set) else IO.unit
                  refresh *> member.get.map(ms => Option.when(ms.contains(c) || watch.contains(c) || u.contains(s.user.id))(
                    sse("change", Json.obj("latest" -> id.asJson, "channel" -> c.asJson))))
                case FeedItem.Notified(u) => IO.pure(Option.when(u == s.user.id)(sse("notify", Json.obj())))
              }
              // 接続が切られないよう 20 秒ごとに空の行を送る
              val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
              // ログアウト・ユーザーの無効化・キーの失効の後は流し続けない（1分ごとに確かめて切る）
              val revoked = Stream.awakeEvery[IO](60.seconds).evalMap(_ => sessions.fromRequest(req).map(_.isEmpty))
              val body = (Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++ events.merge(ping)).interruptWhen(revoked)
              Ok(body).map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
            }
            }
        }
      }
  }
