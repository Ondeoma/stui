package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import stui.core.*
import stui.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID

final case class ReminderRow(id: UUID, userId: UUID, text: String, messageId: Option[UUID], remindAt: Instant)

final case class ScheduledRow(id: UUID,
                              channelId: UUID,
                              authorId: UUID,
                              rootId: Option[UUID],
                              body: Json,
                              frequency: String,
                              runDate: Option[LocalDate],
                              runTime: String,
                              weekday: Option[Int],
                              monthday: Option[Int],
                              timezone: String,
                              nextRun: Option[Instant],
                              enabled: Boolean)

/** リマインダーと定期メッセージ。 */
final class ScheduleStore(xa: Transactor[IO], messages: MessageStore) extends TxStore(xa) {

  // ---------------------------------------------------------------- リマインダー

  def reminders(userId: UUID, includeDone: Boolean): IO[List[ReminderDto]] =
    tx((fr"select id, text, remind_at, message_id, fired_at is not null, done_at is not null, created_at from reminder where user_id = $userId" ++
      (if includeDone then Fragment.empty else fr"and done_at is null") ++
      fr"order by done_at is not null, remind_at").query[(
      UUID,
      String,
      Instant,
      Option[UUID],
      Boolean,
      Boolean,
      Instant
    )].to[List].flatMap(_.traverse(r =>
      r._4.flatTraverse(messages.ref).map(m => ReminderDto(r._1, r._2, r._3, m, r._5, r._6, r._7))
    )))

  def createReminder(userId: UUID, text: String, messageId: Option[UUID], at: Instant): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"insert into reminder (id, user_id, text, message_id, remind_at) values ($id, $userId, $text, $messageId, $at)".update.run.as(
      id
    )
  }

  /** 日時を変える（もう一度知らせる）・完了にする。 */
  def updateReminder(userId: UUID, id: UUID, at: Option[Instant], done: Option[Boolean]): IO[Boolean] = tx {
    val sets = List(
      at.map(a => fr"remind_at = $a, fired_at = null"),
      done.map(d => if d then fr"done_at = now()" else fr"done_at = null")
    ).flatten
    if sets.isEmpty then
      sql"select exists(select 1 from reminder where id = $id and user_id = $userId)".query[Boolean].unique
    else (fr"update reminder" ++ Sql.set(sets) ++ fr"where id = $id and user_id = $userId").update.run.map(_ > 0)
  }

  def deleteReminder(userId: UUID, id: UUID): IO[Boolean] =
    tx(sql"delete from reminder where id = $id and user_id = $userId".update.run.map(_ > 0))

  /** 時刻になったリマインダーを1件ずつ処理する（SKIP LOCKED。失敗しても1回で終える）。 */
  def processDueReminders(batch: Int)(fire: ReminderRow => IO[Unit]): IO[Int] =
    processEach(
      sql"""select id, user_id, text, message_id, remind_at from reminder where fired_at is null and done_at is null and remind_at <= now()
            order by remind_at limit $batch for update skip locked""".query[ReminderRow].to[List]
    )(r => fire(r).attempt)((r, _) => sql"update reminder set fired_at = now() where id = ${r.id}".update.run)

  // ---------------------------------------------------------------- 定期メッセージ

  private val cols =
    fr"id, channel_id, author_id, root_id, body, frequency, run_date, run_time, weekday, monthday, timezone, next_run, enabled"

  def scheduledRow(id: UUID): IO[Option[ScheduledRow]] =
    tx((fr"select" ++ cols ++ fr"from scheduled_message where id = $id").query[ScheduledRow].option)

  /** 自分が作った定期メッセージ（channelId を付けるとそのチャンネルの全員の分）。 */
  def scheduled(authorId: Option[UUID], channelId: Option[UUID]): IO[List[ScheduledDto]] = tx(
    (fr"""select s.id, c.id, c.name, c.kind, s.root_id, u.id, u.name, u.avatar_url, u.is_bot, s.body, s.frequency, s.run_date, s.run_time,
            s.weekday, s.monthday, s.timezone, s.next_run, s.enabled, s.last_run_at, s.last_error
          from scheduled_message s join channel c on c.id = s.channel_id left join app_user u on u.id = s.author_id""" ++
      Sql.whereAnd(List(authorId.map(a => fr"s.author_id = $a"), channelId.map(c => fr"s.channel_id = $c")).flatten) ++
      fr"order by s.enabled desc, s.next_run nulls last")
      .query[(
        UUID,
        UUID,
        String,
        String,
        Option[UUID],
        Option[UUID],
        Option[String],
        Option[String],
        Option[Boolean],
        Json,
        String,
        Option[LocalDate],
        String,
        Option[Int],
        Option[Int],
        String,
        Option[Instant],
        Boolean,
        Option[Instant],
        Option[String]
      )].to[List]
      .map(_.map(r =>
        ScheduledDto(
          r._1,
          ChannelBrief(r._2, r._3, r._4),
          r._5,
          (r._6, r._7).mapN((i, n) => UserBrief(i, n, r._8, r._9.getOrElse(false))),
          r._10,
          RichText.toHtml(r._10),
          r._11,
          r._12.map(_.toString),
          r._13,
          r._14,
          r._15,
          r._16,
          r._17,
          r._18,
          r._19,
          r._20
        )
      ))
  )

  def createScheduled(r: ScheduledReq,
                      author: UUID,
                      runDate: Option[LocalDate],
                      tz: String,
                      next: Option[Instant]): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into scheduled_message (id, channel_id, author_id, root_id, body, frequency, run_date, run_time, weekday, monthday, timezone,
            next_run, enabled)
          values ($id, ${r.channelId}, $author, ${r.rootId}, ${r.body}, ${r.frequency}, $runDate, ${r.runTime}, ${r.weekday}, ${r.monthday}, $tz,
            $next, ${r.enabled && next.isDefined})""".update.run.as(id)
  }

  def updateScheduled(id: UUID,
                      r: ScheduledReq,
                      runDate: Option[LocalDate],
                      tz: String,
                      next: Option[Instant]): IO[Unit] = tx(
    sql"""update scheduled_message set channel_id = ${r.channelId}, root_id = ${r.rootId}, body = ${r.body}, frequency = ${r.frequency},
          run_date = $runDate, run_time = ${r.runTime}, weekday = ${r.weekday}, monthday = ${r.monthday}, timezone = $tz, next_run = $next,
          enabled = ${r.enabled && next.isDefined}, last_error = null where id = $id""".update.run.void
  )

  def deleteScheduled(id: UUID): IO[Unit] = tx(sql"delete from scheduled_message where id = $id".update.run.void)

  /**
   * 送る時刻になった定期メッセージを1件ずつ送る（SKIP LOCKED）。send は投稿の結果（失敗の理由）を返す。
   * next で次の送信日時を決める（None なら止める）。止まっていた間の分はまとめて1回だけ送る。
   */
  def processDueScheduled(batch: Int)(send: ScheduledRow => IO[Option[String]])(next: ScheduledRow => Option[Instant])
    : IO[Int] =
    processEach(
      (fr"select" ++ cols ++
        fr"from scheduled_message where enabled and next_run <= now() order by next_run limit $batch for update skip locked")
        .query[ScheduledRow].to[List]
    )(r => send(r).handleError(e => Some(e.getMessage))) {
      (r, error) =>
        val n = next(r)
        sql"""update scheduled_message set last_run_at = now(), last_error = $error, next_run = $n, enabled = ${n.isDefined}
            where id = ${r.id}""".update.run
    }
}
