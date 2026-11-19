package stui.api

import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** リマインダー・定期メッセージの API。 */
final class ScheduleRoutes(
  store: ScheduleStore,
  svc: ScheduleService,
  activity: ActivityStore,
  val sessions: SessionAuth,
  val access: Access2)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- リマインダー
    case req @ GET -> Root / "api" / "reminders" => getReminders(req)
    case req @ POST -> Root / "api" / "reminders" => postReminders(req)
    case req @ PATCH -> Root / "api" / "reminders" / UuidVar(id) => patchReminder(req, id)
    case req @ DELETE -> Root / "api" / "reminders" / UuidVar(id) => deleteReminder(req, id)
    case req @ GET -> Root / "api" / "scheduled" => getScheduled(req)
    case req @ POST -> Root / "api" / "scheduled" => postScheduled(req)
    case req @ PUT -> Root / "api" / "scheduled" / UuidVar(id) => putScheduled(req, id)
    case req @ DELETE -> Root / "api" / "scheduled" / UuidVar(id) => deleteScheduled(req, id)
  }

  private def getReminders(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      store.reminders(s.user.id, param(req, "done").contains("true")).flatMap(rs =>
        // 今は見られなくなったメッセージ（非公開チャンネルから外されたなど）の抜粋は出さない
        rs.traverse(r =>
          r.message.fold(IO.pure(r))(m =>
            access.message(s, m.id, Need.Read).map(ok => if ok.isRight then r else r.copy(message = None))
          )
        )
          .flatMap(Ok(_))
      )
    )

  private def postReminders(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[ReminderReq](req) { r =>
        val text = r.text.getOrElse("").trim.take(1000)
        if text.isEmpty && r.messageId.isEmpty then err(ErrorCode.ReminderTextRequired)
        else if r.remindAt.isBefore(Instant.now().minusSeconds(60)) then err(ErrorCode.ReminderTimeInPast)
        else
          r.messageId.traverse[IO, Either[AppError, (ChannelCtx, MessageRow)]](m =>
            access.message(s, m, Need.Read)
          ).flatMap {
            case Some(Left(f)) => fail(f)
            case _ => store.createReminder(s.user.id, text, r.messageId, r.remindAt) *>
                store.reminders(s.user.id, false).flatMap(Created(_))
          }
      }
    )

  private def patchReminder(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[ReminderPatch](req)(r =>
        store.updateReminder(s.user.id, id, r.remindAt, r.done).flatMap(ok =>
          if ok then NoContent() else err(ErrorCode.ReminderNotFound)
        )
      )
    )

  private def deleteReminder(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      store.deleteReminder(s.user.id, id).flatMap(ok =>
        if ok then NoContent() else err(ErrorCode.ReminderNotFound)
      )
    )

  private def getScheduled(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      param(req, "channelId").flatMap(uuidOpt) match {
        // チャンネルのメンバーだけ。ほかの人が作ったものは本文を見せない（送るまでは作った人のもの）
        case Some(c) => respond(access.channel(s, c, Need.Member))(_ =>
            store.scheduled(None, Some(c)).flatMap(xs =>
              Ok(xs.map(x =>
                if x.author.exists(_.id == s.user.id) then x else x.copy(body = RichText.empty, html = "")
              ))
            )
          )
        case None => store.scheduled(Some(s.user.id), None).flatMap(Ok(_))
      }
    )

  private def postScheduled(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[ScheduledReq](req)(r =>
        validate(s, r) match {
          case Left(e) => err(ErrorCode.ScheduledInvalid, e)
          case Right(d) =>
            respond(access.channel(s, r.channelId, Need.Post)) { _ =>
              val next = svc.nextRun(r.frequency, d, r.runTime, r.weekday, r.monthday, s.user.timezone)
              if next.isEmpty then err(ErrorCode.ScheduledTimePassed)
              else
                store.createScheduled(r, s.user.id, d, s.user.timezone, next) *>
                  store.scheduled(Some(s.user.id), None).flatMap(Created(_))
            }
        }
      )
    )

  private def putScheduled(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withScheduled(req, id)(
      (
        s,
        _
      ) =>
        body[ScheduledReq](req)(r =>
          validate(s, r) match {
            case Left(e) => err(ErrorCode.ScheduledInvalid, e)
            case Right(d) =>
              respond(access.channel(s, r.channelId, Need.Post)) { _ =>
                val next = svc.nextRun(r.frequency, d, r.runTime, r.weekday, r.monthday, s.user.timezone)
                if next.isEmpty && r.enabled then err(ErrorCode.ScheduledTimePassed)
                else store.updateScheduled(id, r, d, s.user.timezone, next) *> NoContent()
              }
          }
        )
    )

  private def deleteScheduled(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withScheduled(req, id, delete = true)(
      (
        s,
        r
      ) =>
        store.deleteScheduled(id) *>
          activity.audit(
            Some(s.user.id),
            s.viaString,
            "scheduled.delete",
            Some(id.toString),
            Json.obj()
          ).whenA(r.authorId != s.user.id) *> NoContent()
    )

  private def validate(
    s: AuthSession,
    r: ScheduledReq
  ): Either[String, Option[LocalDate]] =
    for {
      _ <- Either.cond(
        Recurrence.Frequencies.contains(r.frequency),
        (),
        "frequency は once・daily・weekdays・weekly・monthly です"
      )
      _ <- Recurrence.parseTime(r.runTime).toRight("時刻は HH:mm で指定してください")
      d <- r.runDate.filter(_.trim.nonEmpty).traverse(v =>
        scala.util.Try(LocalDate.parse(v.trim)).toOption.toRight("日付は yyyy-MM-dd で指定してください")
      )
      _ <- Either.cond(r.frequency != "once" || d.isDefined, (), "1回だけのときは日付を指定してください")
      _ <- Either.cond(r.frequency != "weekly" || r.weekday.exists(w => w >= 1 && w <= 7), (), "曜日（1=月〜7=日）を指定してください")
      _ <- Either.cond(r.frequency != "monthly" || r.monthday.exists(m => m >= 1 && m <= 31), (), "日（1〜31）を指定してください")
      _ <- Either.cond(RichText.valid(r.body) && !RichText.isBlank(r.body), (), "メッセージを入力してください")
    } yield d

  /** 定期メッセージを扱う（変えられるのは作った人だけ。管理者は削除だけできる）。 */
  private def withScheduled(
    req: Request[IO],
    id: UUID,
    delete: Boolean = false
  )(
    f: (AuthSession, ScheduledRow) => IO[Response[IO]]
  ): IO[Response[IO]] =
    authed(req)(s =>
      store.scheduledRow(id).flatMap {
        case Some(r) if r.authorId == s.user.id || (delete && s.canAdmin) => f(s, r)
        case _ => err(ErrorCode.ScheduledMessageNotFound)
      }
    )

}
