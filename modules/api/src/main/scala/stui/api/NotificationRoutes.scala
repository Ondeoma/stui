package stui.api

import cats.effect.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.db.*
import stui.shared.*

import java.time.Instant

/** 画面内の通知。 */
final class NotificationRoutes(activity: ActivityStore, val sessions: SessionAuth, val access: Access2)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "notifications" => getNotifications(req)
    case req @ GET -> Root / "api" / "notifications" / "unread" => getNotificationsUnread(req)
    case req @ POST -> Root / "api" / "notifications" / "read" => postNotificationsRead(req)
    case req @ POST -> Root / "api" / "notifications" / "read-all" => postNotificationsReadAll(req)
  }

  private def getNotifications(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      activity.list(
        s.user.id,
        kinds(req),
        param(req, "unread").contains("true"),
        param(req, "before").flatMap(b => scala.util.Try(Instant.parse(b)).toOption),
        intParam(req, "limit", 50, 200)
      ).flatMap(Ok(_))
    )

  private def getNotificationsUnread(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => activity.unread(s.user.id).flatMap(Ok(_)))

  private def postNotificationsRead(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[MarkReadReq](req)(r => activity.markRead(s.user.id, r.ids.take(500)) *> NoContent()))

  private def postNotificationsReadAll(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[Json](req)(j =>
        activity.markAllRead(
          s.user.id,
          j.hcursor.get[List[String]]("kinds").getOrElse(Nil).filter(NotificationKind.all.contains)
        ) *> NoContent()
      )
    )

  private def kinds(req: Request[IO]): List[String] =
    param(req, "kinds").toList.flatMap(_.split(',').map(_.trim).filter(NotificationKind.all.contains))

}
