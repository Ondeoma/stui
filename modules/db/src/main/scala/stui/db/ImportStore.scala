package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import io.circe.Json

import java.time.Instant
import java.util.UUID

/** 取り込むユーザー（sub は取り込み元を表す. 例: mattermost:<ID>）。 */
final case class ImportUser(
  sub: String,
  name: String,
  email: Option[String],
  isBot: Boolean,
  disabled: Boolean,
  createdAt: Instant)

/** 取り込むチャンネル（公開・非公開）。 */
final case class ImportChannel(
  name: String,
  kind: String,
  topic: String,
  purpose: String,
  archived: Boolean,
  createdBy: Option[UUID],
  createdAt: Instant)

/** 取り込むメッセージ（作った時刻・編集・ピン留めは取り込み元のまま）。 */
final case class ImportMessage(
  id: UUID,
  channelId: UUID,
  rootId: Option[UUID],
  authorId: Option[UUID],
  kind: String,
  body: Json,
  text: String,
  props: Json,
  createdAt: Instant,
  editedAt: Option[Instant],
  pinned: Boolean)

/** ほかのチャットからの取り込み。取り込み元の ID と stui の ID の対応（import_map）を記録し、やり直しても二重に作らない。 */
final class ImportStore(
  xa: Transactor[IO],
  source: String) extends TxStore(xa) {

  /** その種類で取り込み済みのもの（取り込み元の ID → stui の ID）。 */
  def mapped(
    kind: String
  ): IO[Map[String, UUID]] = tx(
    sql"select source_id, target_id from import_map where source = $source and kind = $kind".query[
      (String, UUID)
    ].to[List].map(_.toMap)
  )

  private def record(
    kind: String,
    sourceId: String,
    targetId: UUID
  ): ConnectionIO[Int] =
    sql"""insert into import_map (source, kind, source_id, target_id) values ($source, $kind, $sourceId, $targetId)
          on conflict do nothing""".update.run

  def map(
    kind: String,
    sourceId: String,
    targetId: UUID
  ): IO[Unit] = tx(record(kind, sourceId, targetId)).void

  // ---------------------------------------------------------------- ユーザー

  /** 同じメールアドレスの人（ボットを除く）。 */
  def userByEmail(
    email: String
  ): IO[Option[UUID]] = tx(
    sql"""select id from app_user where lower(email) = lower($email) and not is_bot order by last_login_at desc nulls last limit 1"""
      .query[UUID].option
  )

  def insertUser(
    sourceId: String,
    u: ImportUser
  ): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into app_user (id, sub, name, email, is_bot, disabled, created_at)
          values ($id, ${u.sub}, ${u.name}, ${u.email}, ${u.isBot}, ${u.disabled}, ${u.createdAt})
          on conflict (sub) do update set name = excluded.name returning id""".query[UUID].unique.flatTap(record(
      "user",
      sourceId,
      _
    ))
  }

  /** 表示名（メンションの文字）。 */
  def userNames(
    ids: List[UUID]
  ): IO[Map[UUID, String]] =
    ids.toNel.fold(IO.pure(Map.empty[UUID, String]))(nel =>
      tx((fr"select id, name from app_user where" ++ Fragments.in(fr"id", nel)).query[(UUID, String)].to[List].map(
        _.toMap
      ))
    )

  // ---------------------------------------------------------------- チャンネル

  /** 公開・非公開のチャンネルの名前が使われているか。 */
  def channelNameTaken(
    name: String
  ): IO[Boolean] = tx(
    sql"select exists(select 1 from channel where lower(name) = lower($name) and kind in ('public', 'private'))".query[
      Boolean
    ].unique
  )

  def insertChannel(
    sourceId: String,
    c: ImportChannel
  ): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into channel (id, name, kind, topic, purpose, archived, created_by, created_at)
          values ($id, ${c.name}, ${c.kind}, ${c.topic}, ${c.purpose}, ${c.archived}, ${c.createdBy}, ${c.createdAt})""".update.run *>
      record("channel", sourceId, id).as(id)
  }

  /** DM・グループ DM（同じ顔ぶれのものがあればそれを使う）。 */
  def dmChannel(
    sourceId: String,
    key: String,
    kind: String,
    createdAt: Instant
  ): IO[UUID] = tx(
    sql"""insert into channel (id, name, kind, dm_key, created_at) values (${UUID.randomUUID()}, $key, $kind, $key, $createdAt)
          on conflict (dm_key) do nothing""".update.run *>
      sql"select id from channel where dm_key = $key".query[UUID].unique.flatTap(record("channel", sourceId, _))
  )

  /** メンバーにする（すでにいれば何もしない）。新しく加えたものを返す（既読の位置を合わせるため）。 */
  def addMembers(
    rows: List[(UUID, UUID, Boolean)]
  ): IO[List[(UUID, UUID)]] = tx(
    rows.traverse {
      (
        c,
        u,
        admin
      ) =>
        sql"""insert into channel_member (channel_id, user_id, role) values ($c, $u, ${
            if admin then "admin" else "member"
          })
            on conflict do nothing""".update.run.map(n => Option.when(n > 0)((c, u)))
    }.map(_.flatten)
  )

  // ---------------------------------------------------------------- メッセージ・リアクション・添付・絵文字

  /** まとめて書く（並び順の通し番号 seq は渡した順に振られる）。 */
  def insertMessages(
    rows: List[(String, ImportMessage)]
  ): IO[Int] = tx(
    Update[(
      UUID,
      UUID,
      Option[UUID],
      Option[UUID],
      String,
      Json,
      String,
      Json,
      Instant,
      Option[Instant],
      Option[Instant]
    )](
      """insert into message (id, channel_id, root_id, author_id, kind, via, body, body_text, props, created_at, edited_at, pinned_at)
         values (?, ?, ?, ?, ?, 'import', ?, ?, ?, ?, ?, ?)"""
    )
      .updateMany(rows.map(
        (
          _,
          m
        ) =>
          (
            m.id,
            m.channelId,
            m.rootId,
            m.authorId,
            m.kind,
            m.body,
            m.text,
            m.props,
            m.createdAt,
            m.editedAt,
            Option.when(m.pinned)(m.createdAt)
          )
      )) *>
      Update[(String, String, String, UUID)](
        "insert into import_map (source, kind, source_id, target_id) values (?, ?, ?, ?) on conflict do nothing"
      )
        .updateMany(rows.map(
          (
            sid,
            m
          ) => (source, "post", sid, m.id)
        ))
  )

  def addReactions(
    rows: List[(UUID, UUID, String, Instant)]
  ): IO[Int] = tx(
    Update[(UUID, UUID, String, Instant)](
      "insert into reaction (message_id, user_id, emoji, created_at) values (?, ?, ?, ?) on conflict do nothing"
    )
      .updateMany(rows)
  )

  /** メッセージのチャンネルと時刻（添付を結び付けるため）。 */
  def messageInfo(
    id: UUID
  ): IO[Option[(UUID, Instant)]] =
    tx(sql"select channel_id, created_at from message where id = $id".query[(UUID, Instant)].option)

  /** 投稿済みの添付として登録し、ファイル名を検索の文字に加える。 */
  def addAttachment(
    sourceId: String,
    messageId: UUID,
    channelId: UUID,
    key: String,
    name: String,
    size: Long,
    contentType: String,
    sha: String,
    by: Option[UUID],
    at: Instant
  ): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into attachment (id, channel_id, message_id, s3_key, file_name, size, content_type, sha256, created_by, created_at)
          values ($id, $channelId, $messageId, $key, $name, $size, $contentType, $sha, $by, $at)""".update.run *>
      sql"update message set body_text = body_text || ${"\n" + name} where id = $messageId".update.run *>
      record("file", sourceId, id).as(id)
  }

  /** カスタム絵文字（同じ名前がすでにあれば作らない）。作ったら true。 */
  def addEmoji(
    sourceId: String,
    name: String,
    key: String,
    contentType: String,
    by: Option[UUID]
  ): IO[Boolean] = tx {
    val id = UUID.randomUUID()
    sql"""insert into custom_emoji (id, name, s3_key, content_type, created_by) values ($id, $name, $key, $contentType, $by)
          on conflict (name) do nothing""".update.run.flatMap(n =>
      if n > 0 then record("emoji", sourceId, id).as(true) else false.pure
    )
  }

  def emojiExists(
    name: String
  ): IO[Boolean] = tx(sql"select exists(select 1 from custom_emoji where name = $name)".query[Boolean].unique)

  // ---------------------------------------------------------------- 仕上げ

  /** 新しく加えたメンバーの既読の位置を、取り込み元で最後に見た時刻までにする。 */
  def setLastRead(
    rows: List[(UUID, UUID, Instant)]
  ): IO[Int] = tx(
    Update[(Instant, UUID, UUID)](
      """update channel_member cm set last_read_seq = coalesce((select max(m.seq) from message m
           where m.channel_id = cm.channel_id and m.created_at <= ?), 0)
         where cm.channel_id = ? and cm.user_id = ?"""
    ).updateMany(rows.map(
      (
        c,
        u,
        at
      ) => (at, c, u)
    ))
  )

  /** スレッドの返信の数・最後の返信、スレッドのフォロー（参加した人. 既読）、チャンネルの最後のメッセージの時刻を合わせる。 */
  def finish: IO[Unit] = tx {
    val imported = fr"select target_id from import_map where source = $source"
    (fr"""update message r set reply_count = s.n, last_reply_at = s.last
          from (select root_id, count(*)::int as n, max(created_at) as last from message where root_id is not null and deleted_at is null group by root_id) s
          where r.id = s.root_id and r.id in (""" ++ imported ++ fr"and kind = 'post')").update.run *>
      (fr"""insert into thread_follow (root_id, user_id, last_read_seq)
            select t.root, p.author_id, t.maxseq
            from (select r.id as root, max(m.seq) as maxseq from message r join message m on m.root_id = r.id
                  where r.id in (""" ++ imported ++ fr"""and kind = 'post') group by r.id) t
            join message p on (p.id = t.root or p.root_id = t.root) and p.author_id is not null
            group by t.root, p.author_id, t.maxseq
            on conflict do nothing""").update.run *>
      (fr"""update channel c set last_message_at = s.last
            from (select channel_id, max(created_at) as last from message where deleted_at is null group by channel_id) s
            where c.id = s.channel_id and c.id in (""" ++ imported ++ fr"and kind = 'channel')").update.run.void
  }
}
