package stui.db

import sbirds.db.*
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

final case class IncomingRow(id: UUID,
                             channelId: UUID,
                             name: String,
                             username: Option[String],
                             iconUrl: Option[String],
                             enabled: Boolean,
                             createdBy: Option[UUID],
                             iconEmoji: Option[String])
final case class OutgoingRow(id: UUID,
                             channelId: Option[UUID],
                             name: String,
                             url: String,
                             secret: String,
                             events: List[String],
                             triggerWords: List[String],
                             enabled: Boolean,
                             createdBy: Option[UUID])
final case class DeliveryRow(id: UUID,
                             webhookId: UUID,
                             channelId: Option[UUID],
                             url: String,
                             secret: String,
                             event: String,
                             payload: Json,
                             attempts: Int)

/** Webhook（受信・送信）と、送信のアウトボックス。 */
final class HookStore(xa: Transactor[IO]) extends TxStore(xa) {

  private def brief(id: Option[UUID], n: Option[String], a: Option[String], b: Option[Boolean]) =
    (id, n).mapN((i, x) => UserBrief(i, x, a, b.getOrElse(false)))

  // ---------------------------------------------------------------- 受信

  private val inSelect =
    fr"""select h.id, c.id, c.name, c.kind, h.name, h.username, h.icon_url, h.icon_emoji, h.token_hint, h.enabled, u.id, u.name, u.avatar_url, u.is_bot, h.created_at
         from incoming_webhook h join channel c on c.id = h.channel_id left join app_user u on u.id = h.created_by"""

  private type InRow = (
    UUID,
    UUID,
    String,
    String,
    String,
    Option[String],
    Option[String],
    Option[String],
    String,
    Boolean,
    Option[UUID],
    Option[String],
    Option[String],
    Option[Boolean],
    Instant
  )

  private def inDto(r: InRow) =
    IncomingHookDto(
      r._1,
      ChannelBrief(r._2, r._3, r._4),
      r._5,
      r._6,
      r._7,
      r._8,
      r._9,
      r._10,
      brief(r._11, r._12, r._13, r._14),
      r._15
    )

  /** channelIds が None ならすべて（管理者）。 */
  def incoming(channelIds: Option[List[UUID]]): IO[List[IncomingHookDto]] = tx(
    (inSelect ++ channelIds.fold(Fragment.empty)(ids => fr"where h.channel_id = any(${ids.toArray})") ++
      fr"order by c.name, h.name")
      .query[InRow].map(inDto).to[List]
  )

  def incomingDto(id: UUID): IO[Option[IncomingHookDto]] =
    tx((inSelect ++ fr"where h.id = $id").query[InRow].map(inDto).option)

  def incomingRow(id: UUID): IO[Option[IncomingRow]] =
    tx(sql"select id, channel_id, name, username, icon_url, enabled, created_by, icon_emoji from incoming_webhook where id = $id".query[
      IncomingRow
    ].option)

  def incomingByToken(hash: Array[Byte]): IO[Option[IncomingRow]] =
    tx(sql"select id, channel_id, name, username, icon_url, enabled, created_by, icon_emoji from incoming_webhook where token_hash = $hash".query[
      IncomingRow
    ].option)

  def createIncoming(r: IncomingHookReq, hash: Array[Byte], hint: String, by: UUID): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into incoming_webhook (id, channel_id, name, token_hash, token_hint, username, icon_url, icon_emoji, enabled, created_by)
          values ($id, ${r.channelId}, ${r.name}, $hash, $hint, ${r.username}, ${r.iconUrl}, ${r.iconEmoji}, ${r.enabled}, $by)""".update.run.as(
      id
    )
  }

  def updateIncoming(id: UUID, r: IncomingHookReq): IO[Unit] = tx(
    sql"""update incoming_webhook set channel_id = ${r.channelId}, name = ${r.name}, username = ${r.username}, icon_url = ${r.iconUrl},
          icon_emoji = ${r.iconEmoji}, enabled = ${r.enabled} where id = $id""".update.run.void
  )

  def deleteIncoming(id: UUID): IO[Unit] = tx(sql"delete from incoming_webhook where id = $id".update.run.void)

  // ---------------------------------------------------------------- 送信

  private val outCols = fr"id, channel_id, name, url, secret, events, trigger_words, enabled, created_by"

  def outgoingRow(id: UUID): IO[Option[OutgoingRow]] =
    tx((fr"select" ++ outCols ++ fr"from outgoing_webhook where id = $id").query[OutgoingRow].option)

  /** channelIds が None ならすべて（管理者）。 */
  def outgoing(channelIds: Option[List[UUID]]): IO[List[OutgoingHookDto]] = tx(
    (fr"""select h.id, c.id, c.name, c.kind, h.name, h.url, h.secret, h.events, h.trigger_words, h.enabled, u.id, u.name, u.avatar_url, u.is_bot,
            h.created_at from outgoing_webhook h left join channel c on c.id = h.channel_id left join app_user u on u.id = h.created_by""" ++
      channelIds.fold(Fragment.empty)(ids => fr"where h.channel_id = any(${ids.toArray})") ++ fr"order by h.name")
      .query[(
        UUID,
        Option[UUID],
        Option[String],
        Option[String],
        String,
        String,
        String,
        List[String],
        List[String],
        Boolean,
        Option[UUID],
        Option[String],
        Option[String],
        Option[Boolean],
        Instant
      )]
      .map(r =>
        OutgoingHookDto(
          r._1,
          (r._2, r._3, r._4).mapN(ChannelBrief.apply),
          r._5,
          r._6,
          r._7,
          r._8,
          r._9,
          r._10,
          brief(r._11, r._12, r._13, r._14),
          r._15
        )
      ).to[List]
  )

  def createOutgoing(r: OutgoingHookReq, secret: String, by: UUID): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into outgoing_webhook (id, channel_id, name, url, secret, events, trigger_words, enabled, created_by)
          values ($id, ${r.channelId}, ${r.name}, ${r.url}, $secret, ${r.events.toArray}, ${r.triggerWords.toArray}, ${r.enabled}, $by)"""
      .update.run.as(id)
  }

  def updateOutgoing(id: UUID, r: OutgoingHookReq): IO[Unit] = tx(
    sql"""update outgoing_webhook set channel_id = ${r.channelId}, name = ${r.name}, url = ${r.url}, events = ${r.events.toArray},
          trigger_words = ${r.triggerWords.toArray}, enabled = ${r.enabled} where id = $id""".update.run.void
  )

  def deleteOutgoing(id: UUID): IO[Unit] = tx(sql"delete from outgoing_webhook where id = $id".update.run.void)

  /**
   * 送信の Webhook に積む。チャンネルを指定していないものは公開チャンネルのイベントだけ。text はトリガーの語の判定用。
   * 非公開チャンネルのものは、作った人が今もメンバーのときだけ（外された人の送信先へ中身を送り続けない）。
   */
  def enqueue(channelId: UUID, publicChannel: Boolean, event: String, payload: Json, text: Option[String]): IO[Int] =
    tx(
      sql"""insert into webhook_delivery (id, webhook_id, event, payload)
          select gen_random_uuid(), h.id, $event, $payload from outgoing_webhook h
          where h.enabled and (h.channel_id = $channelId or (h.channel_id is null and $publicChannel))
            and ($publicChannel or h.channel_id is null
                 or exists(select 1 from channel_member cm join app_user u on u.id = cm.user_id
                           where cm.channel_id = h.channel_id and cm.user_id = h.created_by and not u.disabled))
            and (cardinality(h.events) = 0 or $event = any(h.events))
            and (cardinality(h.trigger_words) = 0 or $event <> 'message.created'
                 or exists(select 1 from unnest(h.trigger_words) w where position(lower(w) in lower(${text.getOrElse(
          ""
        )})) = 1))"""
        .update.run
    )

  def deliveries(webhookId: UUID, limit: Int): IO[List[DeliveryDto]] = tx(
    sql"""select id, event, attempts, status_code, last_error, done_at, failed, created_at, payload from webhook_delivery
          where webhook_id = $webhookId order by created_at desc limit $limit""".query[DeliveryDto].to[List]
  )

  /** 送信する分を取り出して send で送り、結果を記録する（SKIP LOCKED）。send は応答の本文も返す（返信の投稿に使う）。 */
  def processDeliveries(batch: Int)(send: DeliveryRow => IO[(OutboxResult, Option[Int])]): IO[Int] =
    processEach(
      sql"""select d.id, d.webhook_id, w.channel_id, w.url, w.secret, d.event, d.payload, d.attempts from webhook_delivery d
            join outgoing_webhook w on w.id = d.webhook_id
            where d.done_at is null and not d.failed and d.not_before <= now() order by d.not_before limit $batch for update of d skip locked"""
        .query[DeliveryRow].to[List]
    )(d => send(d).handleError(e => (OutboxResult.Retry(e.getMessage), None))) {
      case (d, (OutboxResult.Sent, code)) =>
        sql"update webhook_delivery set done_at = now(), attempts = attempts + 1, status_code = $code where id = ${d.id}".update.run
      case (d, (OutboxResult.Skip(r), code)) =>
        sql"update webhook_delivery set failed = true, last_error = $r, status_code = $code where id = ${d.id}".update.run
      case (d, (OutboxResult.Retry(err), code)) =>
        sql"""update webhook_delivery set attempts = attempts + 1, last_error = $err, status_code = $code, failed = ${d.attempts +
            1 >= 8},
              not_before = now() + make_interval(secs => ${Outbox.backoffSeconds(
            d.attempts
          )}) where id = ${d.id}""".update.run
    }

  def purgeDeliveries(days: Int): IO[Int] = tx(
    sql"delete from webhook_delivery where (done_at is not null or failed) and created_at < now() - make_interval(days => $days)".update.run
  )
}
