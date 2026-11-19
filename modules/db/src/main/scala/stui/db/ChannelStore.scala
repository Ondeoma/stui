package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import stui.core.*
import stui.shared.*

import java.time.Instant
import java.util.UUID

final case class ChannelRow(id: UUID, name: String, kind: String, topic: String, purpose: String, dmKey: Option[String], archived: Boolean,
    autoJoin: Boolean, createdBy: Option[UUID], createdAt: Instant, lastMessageAt: Option[Instant]):
  def brief: ChannelBrief = ChannelBrief(id, name, kind)
  def isDm: Boolean = ChannelKind.isDm(kind)
  def isPublic: Boolean = kind == ChannelKind.Public

final case class MemberRow(role: String, notifyLevel: String, muted: Boolean, lastReadSeq: Long)

/** 通知の宛先を決めるためのメンバーの情報。 */
final case class Recipient(userId: UUID, notifyLevel: String, muted: Boolean, isBot: Boolean)

/** チャンネル・メンバー・チャンネルのブックマーク。 */
final class ChannelStore(xa: Transactor[IO]) extends TxStore(xa):

  private val cols = fr"c.id, c.name, c.kind, c.topic, c.purpose, c.dm_key, c.archived, c.auto_join, c.created_by, c.created_at, c.last_message_at"

  def find(id: UUID): IO[Option[ChannelRow]] = tx(findC(id))

  def findC(id: UUID): ConnectionIO[Option[ChannelRow]] = (fr"select" ++ cols ++ fr"from channel c where c.id = $id").query[ChannelRow].option

  def byName(name: String): IO[Option[ChannelRow]] = tx(
    (fr"select" ++ cols ++ fr"from channel c where lower(c.name) = lower($name) and c.kind in ('public', 'private')").query[ChannelRow].option
  )

  def member(channelId: UUID, userId: UUID): IO[Option[MemberRow]] = tx(memberC(channelId, userId))

  def memberC(channelId: UUID, userId: UUID): ConnectionIO[Option[MemberRow]] =
    sql"select role, notify, muted, last_read_seq from channel_member where channel_id = $channelId and user_id = $userId".query[MemberRow].option

  /** チャンネルとメンバーの情報（権限の確認用）。 */
  def access(channelId: UUID, userId: UUID): IO[Option[(ChannelRow, Option[MemberRow])]] = tx(
    findC(channelId).flatMap(_.traverse(c => memberC(channelId, userId).map(c -> _)))
  )

  // ---------------------------------------------------------------- 一覧（自分にとっての未読・メンションの数つき）

  private type CRow = (UUID, String, String, String, String, Boolean, Boolean, Int, Instant, Option[Instant], Boolean, Option[String],
      Option[Boolean], Option[Boolean], Option[String], Int, Int, Option[Long])

  /** 自分の未読（人のメッセージだけ。100件まで数える）・メンションの数。 */
  private def listSelect(uid: UUID) =
    fr"""select c.id, c.name, c.kind, c.topic, c.purpose, c.archived, c.auto_join,
           (select count(*) from channel_member x join app_user xu on xu.id = x.user_id where x.channel_id = c.id and not xu.is_bot)::int,
           c.created_at, c.last_message_at, cm.user_id is not null, cm.role, cm.favorite, cm.muted, cm.notify,
           coalesce((select count(*) from (select 1 from message m where m.channel_id = c.id and m.seq > cm.last_read_seq and m.deleted_at is null
              and (m.root_id is null or m.also_channel) and m.kind <> 'system' and m.author_id is distinct from $uid limit 100) u), 0)::int,
           coalesce((select count(*) from notification n where n.user_id = $uid and n.channel_id = c.id and n.read_at is null
              and n.kind in ('mention', 'dm')), 0)::int,
           cm.last_read_seq
         from channel c left join channel_member cm on cm.channel_id = c.id and cm.user_id = $uid"""

  private def toDtos(uid: UUID, rows: List[CRow]): ConnectionIO[List[ChannelDto]] =
    val dmIds = rows.filter(r => ChannelKind.isDm(r._3)).map(_._1)
    val dmUsers: ConnectionIO[Map[UUID, List[UserBrief]]] =
      if dmIds.isEmpty then Map.empty.pure[ConnectionIO]
      else
        sql"""select cm.channel_id, u.id, u.name, u.avatar_url, u.is_bot from channel_member cm join app_user u on u.id = cm.user_id
              where cm.channel_id = any(${dmIds.toArray}) order by u.name""".query[(UUID, UserBrief)].to[List]
          .map(_.groupMap(_._1)(_._2).view.mapValues(us => us.filterNot(_.id == uid) match
            case Nil    => us
            case others => others).toMap)
    dmUsers.map(dm => rows.map { r =>
      ChannelDto(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8, r._9, r._10, r._11, r._12, r._13.getOrElse(false), r._14.getOrElse(false),
        r._15.getOrElse(NotifyLevel.Default), if r._11 then r._16 else 0, r._17, r._18.getOrElse(0L), dm.getOrElse(r._1, Nil))
    })

  /** 参加しているチャンネル・DM（隠した DM は除く）。 */
  def listForUser(uid: UUID): IO[List[ChannelDto]] = tx(
    (listSelect(uid) ++ fr"where cm.user_id is not null and not (cm.hidden and c.kind in ('dm', 'group'))" ++
      fr"order by c.kind, lower(c.name)").query[CRow].to[List].flatMap(toDtos(uid, _))
  )

  def get(id: UUID, uid: UUID): IO[Option[ChannelDto]] = tx(
    (listSelect(uid) ++ fr"where c.id = $id").query[CRow].option.flatMap(_.toList.traverse(r => toDtos(uid, List(r))).map(_.flatten.headOption))
  )

  /** 参加できるチャンネルを探す（公開チャンネルと、参加している非公開チャンネル）。 */
  def browse(uid: UUID, q: Option[String], archived: Boolean, limit: Int): IO[List[ChannelDto]] = tx {
    val where = List(
      Some(fr"(c.kind = 'public' or (c.kind = 'private' and cm.user_id is not null))"),
      Some(fr"c.archived = $archived"),
      q.map(_.trim).filter(_.nonEmpty).map(s => fr"(array[c.name, c.topic, c.purpose] &@~ ${SearchQuery.phrase(s)} or c.name ilike ${"%" + s + "%"})")
    ).flatten
    (listSelect(uid) ++ Sql.whereAnd(where) ++ fr"order by c.last_message_at desc nulls last, lower(c.name) limit $limit").query[CRow].to[List]
      .flatMap(toDtos(uid, _))
  }

  /** 管理画面: すべてのチャンネル（DM は除く）。 */
  def all(uid: UUID): IO[List[ChannelDto]] = tx(
    (listSelect(uid) ++ fr"where c.kind in ('public', 'private') order by c.archived, lower(c.name)").query[CRow].to[List].flatMap(toDtos(uid, _))
  )

  // ---------------------------------------------------------------- 作成・変更

  /** 参加させる（参加した人を返す）。参加したことは変更履歴（member.joined、user_id 付き）にも残し、
    * 開いている画面が参加しているチャンネルを読み直せるようにする（残さないと、新しいチャンネルの他人の投稿が届かない）。
    */
  private def joinC(channelId: UUID, users: List[UUID], role: String, actor: Option[UUID] = None): ConnectionIO[List[UUID]] =
    users.distinct.flatTraverse(u =>
      sql"""with j as (insert into channel_member (channel_id, user_id, role, last_read_seq)
                       values ($channelId, $u, $role, coalesce((select max(seq) from message), 0))
                       on conflict do nothing returning user_id)
            insert into chat_event (channel_id, kind, actor_id, user_id)
            select $channelId, ${EventKind.MemberJoined}, $actor, user_id from j""".update.run.map(n => if n > 0 then List(u) else Nil))

  /** チャンネルを作る（作った人は管理者）。名前が重なれば Conflict。 */
  def create(name: String, kind: String, topic: String, purpose: String, by: UUID, members: List[UUID], autoJoin: Boolean = false)
      : IO[Either[StoreError, ChannelRow]] = tx {
    sql"select exists(select 1 from channel where lower(name) = lower($name) and kind in ('public', 'private'))".query[Boolean].unique.flatMap {
      case true => fail(StoreError.Conflict(s"「$name」という名前のチャンネルはすでにあります"))
      case false =>
        val id = UUID.randomUUID()
        for
          _ <- sql"""insert into channel (id, name, kind, topic, purpose, auto_join, created_by)
                     values ($id, $name, $kind, $topic, $purpose, $autoJoin, $by)""".update.run
          _ <- joinC(id, List(by), ChannelRole.Admin, Some(by))
          ms <- activeC(members.filterNot(_ == by))
          _ <- joinC(id, ms, ChannelRole.Member, Some(by))
          _ <- autoJoinAllC(id, autoJoin)
          c <- findC(id)
        yield Right(c.get)
    }
  }

  /** 自動参加のチャンネルに、全員（有効な人）を参加させる。 */
  private def autoJoinAllC(id: UUID, autoJoin: Boolean): ConnectionIO[Unit] =
    if !autoJoin then ().pure[ConnectionIO]
    else sql"""with j as (insert into channel_member (channel_id, user_id, last_read_seq)
                           select $id, u.id, coalesce((select max(seq) from message), 0) from app_user u where not u.disabled and not u.is_bot
                           on conflict do nothing returning user_id)
               insert into chat_event (channel_id, kind, user_id) select $id, ${EventKind.MemberJoined}, user_id from j""".update.run.void

  /** 既にある DM（作らない）。 */
  def findDm(users: List[UUID]): IO[Option[ChannelRow]] = tx(
    (fr"select" ++ cols ++ fr"from channel c where c.dm_key = ${DmKey.of(users)}").query[ChannelRow].option
  )

  /** DM を開く（同じ顔ぶれの DM があればそれを返す）。 */
  def openDm(users: List[UUID]): IO[ChannelRow] = tx {
    val key  = DmKey.of(users)
    val kind = if users.distinct.size > 2 then ChannelKind.Group else ChannelKind.Dm
    for
      _ <- sql"""insert into channel (id, name, kind, dm_key) values (${UUID.randomUUID()}, $key, $kind, $key)
                 on conflict (dm_key) do nothing""".update.run
      c <- (fr"select" ++ cols ++ fr"from channel c where c.dm_key = $key").query[ChannelRow].unique
      _ <- joinC(c.id, users, ChannelRole.Member)
      _ <- sql"update channel_member set hidden = false where channel_id = ${c.id}".update.run
    yield c
  }

  def update(id: UUID, r: UpdateChannelReq, name: Option[String]): IO[Either[StoreError, Unit]] = tx {
    val dup = name.fold(false.pure[ConnectionIO])(n =>
      sql"select exists(select 1 from channel where lower(name) = lower($n) and kind in ('public', 'private') and id <> $id)".query[Boolean].unique)
    dup.flatMap {
      case true => fail(StoreError.Conflict(s"「${name.getOrElse("")}」という名前のチャンネルはすでにあります"))
      case false =>
        val sets = List(
          name.map(v => fr"name = $v"), r.topic.map(v => fr"topic = ${v.trim.take(250)}"), r.purpose.map(v => fr"purpose = ${v.trim.take(250)}"),
          r.archived.map(v => fr"archived = $v"), r.autoJoin.map(v => fr"auto_join = $v")
        ).flatten
        if sets.isEmpty then Right(()).pure[ConnectionIO]
        else (fr"update channel" ++ Sql.set(sets) ++ fr"where id = $id").update.run *> autoJoinAllC(id, r.autoJoin.contains(true)).as(Right(()))
    }
  }

  def delete(id: UUID): IO[Unit] = tx(sql"delete from channel where id = $id".update.run.void)

  // ---------------------------------------------------------------- メンバー

  /** 参加させる。新しく参加した人を返す。 */
  def addMembers(channelId: UUID, users: List[UUID], actor: Option[UUID] = None): IO[List[UUID]] =
    tx(activeC(users).flatMap(joinC(channelId, _, ChannelRole.Member, actor)))

  /** そのうち、存在して無効になっていないユーザー。 */
  def activeUsers(users: List[UUID]): IO[List[UUID]] = tx(activeC(users))

  private def activeC(users: List[UUID]): ConnectionIO[List[UUID]] =
    if users.isEmpty then List.empty[UUID].pure[ConnectionIO]
    else sql"select id from app_user where id = any(${users.toArray}) and not disabled".query[UUID].to[List]

  def removeMember(channelId: UUID, userId: UUID): IO[Boolean] = tx(
    sql"delete from channel_member where channel_id = $channelId and user_id = $userId".update.run.map(_ > 0)
  )

  def setRole(channelId: UUID, userId: UUID, role: String): IO[Boolean] = tx(
    sql"update channel_member set role = $role where channel_id = $channelId and user_id = $userId".update.run.map(_ > 0)
  )

  def adminCount(channelId: UUID): IO[Int] = tx(
    sql"select count(*) from channel_member where channel_id = $channelId and role = 'admin'".query[Int].unique
  )

  def setPrefs(channelId: UUID, userId: UUID, r: MemberPrefReq): IO[Boolean] = tx {
    val sets = List(r.favorite.map(v => fr"favorite = $v"), r.muted.map(v => fr"muted = $v"),
      r.notifyLevel.filter(NotifyLevel.all.contains).map(v => fr"notify = $v"), r.hidden.map(v => fr"hidden = $v")).flatten
    if sets.isEmpty then memberC(channelId, userId).map(_.isDefined)
    else (fr"update channel_member" ++ Sql.set(sets) ++ fr"where channel_id = $channelId and user_id = $userId").update.run.map(_ > 0)
  }

  /** 既読の位置を進める（戻さない）。 */
  def markRead(channelId: UUID, userId: UUID, seq: Long): IO[Unit] = tx(
    sql"""update channel_member set last_read_seq = greatest(last_read_seq, $seq)
          where channel_id = $channelId and user_id = $userId""".update.run.void
  )

  /** 未読に戻す（seq より前まで読んだことにする）。 */
  def markUnread(channelId: UUID, userId: UUID, seq: Long): IO[Unit] = tx(
    sql"update channel_member set last_read_seq = ${seq - 1} where channel_id = $channelId and user_id = $userId".update.run.void
  )

  def members(channelId: UUID): IO[List[ChannelMemberDto]] = tx(
    sql"""select u.id, u.name, u.avatar_url, u.is_bot, cm.role, cm.joined_at from channel_member cm join app_user u on u.id = cm.user_id
          where cm.channel_id = $channelId and not u.disabled order by cm.role, u.name"""
      .query[(UserBrief, String, Instant)].map(ChannelMemberDto.apply).to[List]
  )

  def recipients(channelId: UUID): ConnectionIO[List[Recipient]] =
    sql"""select cm.user_id, cm.notify, cm.muted, u.is_bot from channel_member cm join app_user u on u.id = cm.user_id
          where cm.channel_id = $channelId and not u.disabled""".query[Recipient].to[List]

  def recipientsIO(channelId: UUID): IO[List[Recipient]] = tx(recipients(channelId))

  def memberIds(channelId: UUID): IO[List[UUID]] = tx(sql"select user_id from channel_member where channel_id = $channelId".query[UUID].to[List])

  /** 参加しているチャンネルの ID（画面への知らせの振り分け）。 */
  def memberChannelIds(userId: UUID): IO[Set[UUID]] = tx(
    sql"select channel_id from channel_member where user_id = $userId".query[UUID].to[List].map(_.toSet)
  )

  /** 自動参加のチャンネルに参加させる（初めてログインしたとき）。チャンネルが1つもなければ #general を作る。 */
  def joinAutoChannels(userId: UUID): IO[List[UUID]] = tx(
    for
      none <- sql"select not exists(select 1 from channel where kind in ('public', 'private'))".query[Boolean].unique
      _ <- if none then createGeneral(userId) else ().pure[ConnectionIO]
      ids <- sql"select id from channel where auto_join and not archived".query[UUID].to[List]
      joined <- ids.flatTraverse(id => joinC(id, List(userId), ChannelRole.Member).map(_.map(_ => id)))
    yield joined
  )

  private def createGeneral(userId: UUID): ConnectionIO[Unit] =
    val id = UUID.randomUUID()
    sql"""insert into channel (id, name, kind, purpose, auto_join, created_by)
          values ($id, 'general', 'public', '全員が参加するチャンネルです', true, $userId)""".update.run *>
      joinC(id, List(userId), ChannelRole.Admin).void

  // ---------------------------------------------------------------- ブックマーク（チャンネルのヘッダーのリンク）

  def bookmarks(channelId: UUID): IO[List[BookmarkDto]] = tx(
    sql"""select b.id, b.channel_id, b.title, b.url, b.emoji, u.id, u.name, u.avatar_url, u.is_bot
          from channel_bookmark b left join app_user u on u.id = b.created_by where b.channel_id = $channelId order by b.position, b.created_at"""
      .query[(UUID, UUID, String, String, String, Option[UUID], Option[String], Option[String], Option[Boolean])]
      .map(r => BookmarkDto(r._1, r._2, r._3, r._4, r._5, (r._6, r._7).mapN((i, n) => UserBrief(i, n, r._8, r._9.getOrElse(false))))).to[List]
  )

  def addBookmark(channelId: UUID, r: BookmarkReq, by: UUID): IO[UUID] = tx {
    val id = UUID.randomUUID()
    sql"""insert into channel_bookmark (id, channel_id, title, url, emoji, position, created_by)
          values ($id, $channelId, ${r.title.trim.take(100)}, ${r.url.trim}, ${r.emoji.getOrElse("").take(16)},
                  coalesce((select max(position) + 1 from channel_bookmark where channel_id = $channelId), 0), $by)""".update.run.as(id)
  }

  def updateBookmark(channelId: UUID, id: UUID, r: BookmarkReq): IO[Boolean] = tx(
    sql"""update channel_bookmark set title = ${r.title.trim.take(100)}, url = ${r.url.trim}, emoji = ${r.emoji.getOrElse("").take(16)}
          where id = $id and channel_id = $channelId""".update.run.map(_ > 0)
  )

  def deleteBookmark(channelId: UUID, id: UUID): IO[Boolean] = tx(
    sql"delete from channel_bookmark where id = $id and channel_id = $channelId".update.run.map(_ > 0)
  )
