package stui.api

import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import stui.core.*
import stui.db.*

import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（チャンネルの変更、ユーザーへの通知）。 */
enum FeedItem {
  case Changed(
    channelId: UUID,
    eventId: Long,
    userId: Option[UUID])
  case Notified(
    userId: UUID)
}

final class StreamRoutes(
  feed: EventFeed,
  channels: ChannelStore,
  val sessions: SessionAuth,
  val access: Access2) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /**
     * 画面への知らせ。参加しているチャンネル（と watch で指定した見ている公開チャンネル）に変更があれば change、
     * 自分への通知があれば notify を送る。届いたら画面が /api/changes で中身を取りに行く。
     */
    case req @ GET -> Root / "api" / "stream" => getStream(req)
  }

  private def getStream(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      val watch = param(req, "watch").flatMap(uuidOpt)
      watch.fold(IO.pure(Right(()): Either[AppError, Unit]))(c => access.channel(s, c, Need.Read).map(_.void)).flatMap {
        case Left(f) => fail(f)
        case _ =>
          feed.subscribe(s.user.id).flatMap {
            case None => err(ErrorCode.TooManyStreams)
            case Some(feedItems) =>
              channels.memberChannelIds(s.user.id).flatMap(Ref.of[IO, Set[UUID]](_)).flatMap { member =>
                val events = feedItems.evalMapFilter {
                  case FeedItem.Changed(c, id, u) =>
                    // 自分が参加・退出したら、参加しているチャンネルを読み直す
                    val refresh = if u.contains(s.user.id) then
                      channels.memberChannelIds(s.user.id).flatMap(member.set)
                    else IO.unit
                    refresh *> member.get.map(ms =>
                      Option.when(ms.contains(c) || watch.contains(c) || u.contains(s.user.id))(
                        sse("change", Json.obj("latest" -> id.asJson, "channel" -> c.asJson))
                      )
                    )
                  case FeedItem.Notified(u) => IO.pure(Option.when(u == s.user.id)(sse("notify", Json.obj())))
                }
                // 接続が切られないよう 20 秒ごとに空の行を送る
                val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
                // ログアウト・ユーザーの無効化・キーの失効の後は流し続けない（1分ごとに確かめて切る）
                val revoked = Stream.awakeEvery[IO](60.seconds).evalMap(_ => sessions.fromRequest(req).map(_.isEmpty))
                val body =
                  (Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++
                    events.merge(ping)).interruptWhen(revoked)
                Ok(body.through(s =>
                  Stream.bracket(sbirds.observe.Metrics.upDown("stui.sse.connections", 1))(_ =>
                    sbirds.observe.Metrics.upDown("stui.sse.connections", -1)
                  ) >> s
                ))
                  .map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
              }
          }
      }
    }

  private def sse(
    kind: String,
    data: Json
  ): ServerSentEvent = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

}
