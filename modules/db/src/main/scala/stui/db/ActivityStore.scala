package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json
import stui.shared.*

import java.time.Instant
import java.util.UUID

/** 送信待ちのメール。 */
final case class MailRow(id: UUID,
                         userId: Option[UUID],
                         toAddress: Option[String],
                         notificationId: Option[UUID],
                         subject: String,
                         body: String,
                         attempts: Int)

/** 画面内の通知・メールの送信待ち・監査ログ。 */
final class ActivityStore(xa: Transactor[IO]) extends TxStore(xa) {

  // ---------------------------------------------------------------- 通知

  def notify(userId: UUID,
             kind: String,
             channelId: Option[UUID],
             messageId: Option[UUID],
             actor: Option[UUID],
             title: String,
             body: String): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into notification (id, user_id, kind, channel_id, message_id, actor_id, title, body)
          values ($id, $userId, $kind, $channelId, $messageId, $actor, $title, $body)""".update.run.as(id)
  }

  private val notifSelect =
    fr"""select n.id, n.kind, c.id, c.name, c.kind, n.message_id, m.root_id, u.id, u.name, u.avatar_url, u.is_bot, n.title, n.body, n.at,
                n.read_at is not null
         from notification n left join channel c on c.id = n.channel_id left join message m on m.id = n.message_id
         left join app_user u on u.id = n.actor_id"""

  private type NRow = (
    UUID,
    String,
    Option[UUID],
    Option[String],
    Option[String],
    Option[UUID],
    Option[UUID],
    Option[UUID],
    Option[String],
    Option[String],
    Option[Boolean],
    String,
    String,
    Instant,
    Boolean
  )

  private def toDto(r: NRow): NotificationDto = {
    val ch = (r._3, r._4, r._5).mapN(ChannelBrief.apply)
    val actor = (r._8, r._9).mapN((id, n) => UserBrief(id, n, r._10, r._11.getOrElse(false)))
    NotificationDto(r._1, r._2, ch, r._6, r._7, actor, r._12, r._13, r._14, r._15)
  }

  /** kinds が空ならすべて。チャンネルを抜けた・見られなくなったものは出さない（チャンネルのない通知は出す）。 */
  def list(userId: UUID,
           kinds: List[String],
           unreadOnly: Boolean,
           before: Option[Instant],
           limit: Int): IO[NotificationListDto] =
    tx(
      for {
        items <-
        (notifSelect ++
          fr"""where n.user_id = $userId and (n.channel_id is null or c.kind = 'public'
                    or exists(select 1 from channel_member cm where cm.channel_id = n.channel_id and cm.user_id = $userId))""" ++
          (if kinds.isEmpty then Fragment.empty else fr"and n.kind = any(${kinds.toArray})") ++
          (if unreadOnly then fr"and n.read_at is null" else Fragment.empty) ++
          before.fold(
            Fragment.empty
          )(b => fr"and n.at < $b") ++ fr"order by n.at desc limit $limit").query[NRow].map(toDto).to[List]
        unread <- sql"select count(*) from notification where user_id = $userId and read_at is null".query[Int].unique
      } yield NotificationListDto(items, unread)
    )

  def unread(userId: UUID): IO[UnreadDto] = tx(
    sql"""select count(*), count(*) filter (where kind in ('mention', 'dm')) from notification where user_id = $userId and read_at is null"""
      .query[(Int, Int)].unique.map(UnreadDto.apply)
  )

  def markRead(userId: UUID, ids: List[UUID]): IO[Unit] = tx(
    sql"update notification set read_at = now() where user_id = $userId and id = any(${ids.toArray}) and read_at is null".update.run.void
  )

  def markAllRead(userId: UUID, kinds: List[String]): IO[Unit] = tx(
    (fr"update notification set read_at = now() where user_id = $userId and read_at is null" ++
      (if kinds.isEmpty then Fragment.empty else fr"and kind = any(${kinds.toArray})")).update.run.void
  )

  /** チャンネルを読んだら、そこまでのメッセージの通知（スレッドの返信は除く）を既読にする。 */
  def markChannelRead(userId: UUID, channelId: UUID, upToSeq: Long): IO[Unit] = tx(
    sql"""update notification n set read_at = now() from message m
          where n.user_id = $userId and n.channel_id = $channelId and n.read_at is null and m.id = n.message_id
            and m.seq <= $upToSeq and (m.root_id is null or m.also_channel)""".update.run.void
  )

  /** スレッドを読んだら、そのスレッドの通知を既読にする。 */
  def markThreadRead(userId: UUID, rootId: UUID): IO[Unit] = tx(
    sql"""update notification n set read_at = now() from message m
          where n.user_id = $userId and n.read_at is null and m.id = n.message_id and (m.id = $rootId or m.root_id = $rootId)""".update.run.void
  )

  /** since より後に通知を受けた人（画面への知らせ）。 */
  def notifiedSince(since: Instant): IO[List[(UUID, Instant)]] =
    tx(sql"select user_id, max(at) from notification where at > $since group by user_id".query[(
      UUID,
      Instant
    )].to[List])

  def purgeOld(days: Int): IO[Int] =
    tx(sql"delete from notification where at < now() - make_interval(days => $days)".update.run)

  // ---------------------------------------------------------------- メール

  /** dedupe_key が同じ未送信のメールがあれば、本文を差し替える（まとめて1通にする）。 */
  def enqueueMail(userId: Option[UUID],
                  to: Option[String],
                  notificationId: Option[UUID],
                  subject: String,
                  body: String,
                  dedupe: Option[String],
                  delaySeconds: Int): IO[Unit] = tx {
    val id = UUID.randomUUID()
    dedupe match {
      case Some(k) =>
        sql"""insert into mail_outbox (id, user_id, to_address, notification_id, subject, body, dedupe_key, not_before)
              values ($id, $userId, $to, $notificationId, $subject, $body, $k, now() + make_interval(secs => $delaySeconds))
              on conflict (dedupe_key) where sent_at is null and not skipped and dedupe_key is not null
              do update set subject = excluded.subject, body = mail_outbox.body || E'\n\n----\n\n' || excluded.body,
                notification_id = excluded.notification_id""".update.run.void
      case None =>
        sql"""insert into mail_outbox (id, user_id, to_address, notification_id, subject, body, not_before)
              values ($id, $userId, $to, $notificationId, $subject, $body, now() + make_interval(secs => $delaySeconds))""".update.run.void
    }
  }

  /** 送信する分を取り出し（SKIP LOCKED で複数ノードの重複を防ぐ）、send の結果を記録する。 */
  def processMail(batch: Int)(send: MailRow => IO[OutboxResult]): IO[Int] =
    processEach(
      sql"""select id, user_id, to_address, notification_id, subject, body, attempts from mail_outbox
            where sent_at is null and not skipped and not_before <= now() order by not_before limit $batch for update skip locked""".query[
        MailRow
      ].to[List]
    )(m => send(m).handleError(e => OutboxResult.Retry(e.getMessage))) {
      case (m, OutboxResult.Sent) =>
        sql"update mail_outbox set sent_at = now(), attempts = attempts + 1 where id = ${m.id}".update.run
      case (m, OutboxResult.Skip(reason)) =>
        sql"update mail_outbox set skipped = true, last_error = $reason where id = ${m.id}".update.run
      case (m, OutboxResult.Retry(err)) =>
        sql"""update mail_outbox set attempts = attempts + 1, last_error = $err, skipped = ${m.attempts + 1 >= 10},
              not_before = now() + make_interval(secs => ${Outbox.backoffSeconds(
            m.attempts
          )}) where id = ${m.id}""".update.run
    }

  /** 通知が既読になっているか（既読ならメールは送らない）。 */
  def notificationRead(id: UUID): IO[Boolean] =
    tx(sql"select coalesce((select read_at is not null from notification where id = $id), true)".query[Boolean].unique)

  /** メールを送ってよいか（ユーザーが有効で、その種類のメールを止めていない）。宛先のアドレスを返す。 */
  def mailTarget(userId: UUID, kind: Option[String]): IO[Option[(String, String)]] = tx(
    sql"""select u.email, u.name from app_user u where u.id = $userId and not u.disabled and not u.is_bot and u.email is not null
          and not exists(select 1 from notify_pref p where p.user_id = u.id and p.kind = ${kind.getOrElse(
        ""
      )} and not p.email)"""
      .query[(String, String)].option
  )

  // ---------------------------------------------------------------- 監査ログ

  def audit(actor: Option[UUID], via: String, action: String, target: Option[String], detail: Json): IO[Unit] = tx(
    sql"insert into audit_log (actor_id, via, action, target, detail) values ($actor, $via, $action, $target, $detail)".update.run.void
  )

  def auditLog(before: Option[Long], action: Option[String], limit: Int): IO[List[AuditDto]] = tx(
    (fr"""select a.id, a.at, u.id, u.name, u.avatar_url, u.is_bot, a.via, a.action, a.target, a.detail
          from audit_log a left join app_user u on u.id = a.actor_id where true""" ++
      before.fold(Fragment.empty)(b => fr"and a.id < $b") ++
      action.fold(Fragment.empty)(x => fr"and a.action like ${x + "%"}") ++
      fr"order by a.id desc limit $limit")
      .query[(
        Long,
        Instant,
        Option[UUID],
        Option[String],
        Option[String],
        Option[Boolean],
        String,
        String,
        Option[String],
        Json
      )]
      .map(r =>
        AuditDto(
          r._1,
          r._2,
          (r._3, r._4).mapN((i, n) => UserBrief(i, n, r._5, r._6.getOrElse(false))),
          r._7,
          r._8,
          r._9,
          r._10
        )
      )
      .to[List]
  )

  def purgeAudit(days: Int): IO[Int] =
    tx(sql"delete from audit_log where at < now() - make_interval(days => $days)".update.run)
}
