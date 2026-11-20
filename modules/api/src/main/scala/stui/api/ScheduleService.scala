package stui.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*

/** リマインダー・定期メッセージの送信（全ノードで動かし、SKIP LOCKED で取り合う）。 */
final class ScheduleService(store: ScheduleStore,
                            messages: MessageStore,
                            channels: ChannelStore,
                            users: UserStore,
                            chat: ChatService,
                            notifier: NotifyService,
                            sessions: SessionAuth) {

  /** stui 自身のボット（リマインダーの送り主）。 */
  def systemBot: IO[User] = users.bot("bot:stui", "stui リマインダー")

  /** 次の送信日時。 */
  def nextRun(freq: String,
              runDate: Option[LocalDate],
              runTime: String,
              weekday: Option[Int],
              monthday: Option[Int],
              tz: String,
              after: Instant = Instant.now()): Option[Instant] =
    Recurrence.parseTime(runTime).flatMap(t => Recurrence.next(freq, runDate, t, weekday, monthday, Op.zone(tz), after))

  private def fireReminder(r: ReminderRow): IO[Unit] =
    for {
      bot <- systemBot
      dm <- channels.openDm(List(r.userId, bot.id))
      // 今も見られるメッセージだけを添える
      ref <- r.messageId.flatTraverse(id =>
        sessions.sessionFor(r.userId).flatMap(_.flatTraverse(s =>
          chat.access.message(s, id, Need.Read).flatMap(_.toOption.flatTraverse(_ => messages.view(id, r.userId)))
        ))
      )
      text = r.text.trim
      md = List(
        Some("⏰ **リマインダー**" + (if text.nonEmpty then s": $text" else "")),
        ref.map(m => s"[メッセージを開く](/m/${m.id})")
      ).flatten.mkString("\n\n")
      res <- chat.publish(
        dm,
        Author(bot, "reminder"),
        RichText.fromMarkdown(md),
        None,
        false,
        Json.obj("reminder" -> Json.obj("id" -> r.id.asJson, "messageId" -> r.messageId.asJson)),
        "bot"
      )
      _ <- res.toOption.traverse_(m =>
        notifier.send(
          NotificationKind.Reminder,
          r.userId,
          None,
          Some(dm.id),
          Some(m.id),
          "リマインダー",
          if text.nonEmpty then text else ref.map(m => RichText.toPlain(m.body).take(200)).getOrElse(""),
          Paths.message(dm.id, Some(m.id), None)
        )
      )
    } yield ()

  private def sendScheduled(r: ScheduledRow): IO[Option[String]] =
    sessions.sessionFor(r.authorId, AuthVia.System("schedule")).flatMap {
      case None => IO.pure(Some("作成した人が無効になっています"))
      case Some(s) => chat.post(s, r.channelId, PostMessageReq(r.body, r.rootId)).map(_.left.toOption.map(_.message))
    }

  def reminderLoop: IO[Nothing] =
    Periodic.run(
      15.seconds,
      20.seconds,
      ErrorCode.ReminderWorkerFailed
    )(store.processDueReminders(50)(fireReminder))

  def scheduledLoop: IO[Nothing] =
    Periodic.run(20.seconds, 30.seconds, ErrorCode.ScheduledWorkerFailed)(
      store.processDueScheduled(50)(sendScheduled)(r =>
        nextRun(r.frequency, r.runDate, r.runTime, r.weekday, r.monthday, r.timezone)
      )
    )
}
