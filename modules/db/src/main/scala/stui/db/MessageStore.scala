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

import java.time.Instant
import java.util.UUID

final case class MessageRow(
  id: UUID,
  seq: Long,
  channelId: UUID,
  rootId: Option[UUID],
  alsoChannel: Boolean,
  authorId: Option[UUID],
  kind: String,
  via: String,
  body: Json,
  bodyText: String,
  props: Json,
  replyCount: Int,
  lastReplyAt: Option[Instant],
  pinnedAt: Option[Instant],
  createdAt: Instant,
  editedAt: Option[Instant],
  deletedAt: Option[Instant])

/** 投稿する内容。 */
final case class NewMessage(
  channelId: UUID,
  rootId: Option[UUID],
  alsoChannel: Boolean,
  authorId: UUID,
  kind: String,
  via: String,
  body: Json,
  text: String,
  props: Json,
  attachmentIds: List[UUID] = Nil)

final case class AttachmentRow(
  id: UUID,
  channelId: UUID,
  messageId: Option[UUID],
  s3Key: String,
  fileName: String,
  size: Long,
  contentType: String,
  createdBy: Option[UUID])

/** メッセージ・スレッド・リアクション・ピン留め・保存・添付・下書き・変更履歴。 */
final class MessageStore(
  xa: Transactor[IO]) extends TxStore(xa) {

  private val cols =
    fr"""m.id, m.seq, m.channel_id, m.root_id, m.also_channel, m.author_id, m.kind, m.via, m.body, m.body_text, m.props,
                          m.reply_count, m.last_reply_at, m.pinned_at, m.created_at, m.edited_at, m.deleted_at"""

  def find(
    id: UUID
  ): IO[Option[MessageRow]] = tx(findC(id))

  def findC(
    id: UUID
  ): ConnectionIO[
    Option[MessageRow]
  ] = (fr"select" ++ cols ++ fr"from message m where m.id = $id").query[MessageRow].option

  def rows(
    ids: List[UUID]
  ): IO[List[MessageRow]] =
    tx((fr"select" ++ cols ++ fr"from message m where m.id = any(${ids.toArray})").query[MessageRow].to[List])

  // ---------------------------------------------------------------- 表示用の形（作者・リアクション・返信した人・添付・保存）

  def views(
    rs: List[MessageRow],
    viewer: UUID
  ): ConnectionIO[List[MessageDto]] =
    if rs.isEmpty then List.empty[MessageDto].pure[ConnectionIO]
    else {
      val ids = rs.map(_.id)
      val roots = rs.filter(_.replyCount > 0).map(_.id)
      for {
        authors <-
          sql"select id, name, avatar_url, is_bot from app_user where id = any(${rs.flatMap(_.authorId).distinct.toArray})"
            .query[UserBrief].to[List].map(_.map(u => u.id -> u).toMap)
        reacts <-
          sql"""select r.message_id, r.emoji, u.id, u.name, u.avatar_url, u.is_bot from reaction r join app_user u on u.id = r.user_id
                        where r.message_id = any(${ids.toArray}) order by r.created_at""".query[(
            UUID,
            String,
            UserBrief
          )].to[List]
        repliers <- if roots.isEmpty then List.empty[(UUID, UserBrief)].pure[ConnectionIO]
        else
          sql"""select x.root_id, u.id, u.name, u.avatar_url, u.is_bot from (
                                 select root_id, author_id, max(seq) s from message where root_id = any(${roots.toArray}) and deleted_at is null
                                 group by root_id, author_id) x join app_user u on u.id = x.author_id order by x.s desc"""
            .query[(UUID, UserBrief)].to[List]
        files <-
          sql"""select id, message_id, file_name, size, content_type from attachment where message_id = any(${ids.toArray}) and not inline order by created_at"""
            .query[(UUID, UUID, String, Long, String)].to[List]
        saved <-
          sql"select message_id from saved_message where user_id = $viewer and message_id = any(${ids.toArray})".query[
            UUID
          ].to[List].map(_.toSet)
      } yield {
        val rm = reacts.groupBy(_._1).view.mapValues(xs =>
          xs.groupBy(_._2).toList.sortBy(
            (
              e,
              _
            ) => xs.indexWhere(_._2 == e)
          ).map(
            (
              e,
              us
            ) => ReactionDto(e, us.map(_._3))
          )
        ).toMap
        val pm = repliers.groupMap(_._1)(_._2).view.mapValues(_.take(5)).toMap
        val fm = files.groupMap(_._2)(f => AttachmentDto(f._1, f._3, f._4, f._5, Files.url(f._1, f._3))).toMap
        rs.map { m =>
          val del = m.deletedAt.isDefined
          MessageDto(
            m.id,
            m.channelId,
            m.seq,
            m.rootId,
            m.alsoChannel,
            m.authorId.flatMap(authors.get),
            m.kind,
            m.via,
            if del then RichText.empty else m.body,
            if del then "" else RichText.toHtml(m.body),
            if del then Json.obj() else m.props,
            m.createdAt,
            m.editedAt,
            del,
            m.replyCount,
            m.lastReplyAt,
            pm.getOrElse(m.id, Nil),
            if del then Nil else rm.getOrElse(m.id, Nil),
            m.pinnedAt.isDefined && !del,
            saved(m.id),
            if del then Nil else fm.getOrElse(m.id, Nil)
          )
        }
      }
    }

  def viewsIO(
    rs: List[MessageRow],
    viewer: UUID
  ): IO[List[MessageDto]] = tx(views(rs, viewer))

  def view(
    id: UUID,
    viewer: UUID
  ): IO[Option[MessageDto]] =
    tx(findC(id).flatMap(_.toList.traverse(r => views(List(r), viewer)).map(_.flatten.headOption)))

  def viewsByIds(
    ids: List[UUID],
    viewer: UUID
  ): IO[List[MessageDto]] = tx(
    (fr"select" ++ cols ++ fr"from message m where m.id = any(${ids.toArray}) order by m.seq").query[
      MessageRow
    ].to[List].flatMap(views(_, viewer))
  )

  // ---------------------------------------------------------------- 一覧

  /** チャンネルに出すもの（親メッセージと、チャンネルにも出す返信。消したものは返信が残っているときだけ）。 */
  private def visibleInChannel(
    channelId: UUID
  ) =
    fr"m.channel_id = $channelId and (m.root_id is null or m.also_channel) and (m.deleted_at is null or m.reply_count > 0)"

  /**
   * チャンネルのメッセージ. before / after は seq（その前・後の limit 件）、around はその seq を中心に前後.
   * 何も指定しなければ最新の limit 件。古い順に返す。
   */
  def page(
    channelId: UUID,
    viewer: UUID,
    before: Option[Long],
    after: Option[Long],
    around: Option[Long],
    limit: Int
  ): IO[MessagePageDto] = tx {
    def older(
      b: Option[Long],
      n: Int
    ) =
      (fr"select" ++ cols ++ fr"from message m where" ++ visibleInChannel(channelId) ++
        b.fold(Fragment.empty)(s => fr"and m.seq < $s") ++
        fr"order by m.seq desc limit ${n + 1}").query[MessageRow].to[List]
    def newer(
      a: Long,
      n: Int
    ) =
      (fr"select" ++ cols ++ fr"from message m where" ++ visibleInChannel(channelId) ++
        fr"and m.seq > $a order by m.seq limit ${n + 1}")
        .query[MessageRow].to[List]
    val q: ConnectionIO[(List[MessageRow], Boolean, Boolean)] = (before, after, around) match {
      case (_, Some(a), _) => newer(a, limit).map(xs => (xs.take(limit), true, xs.size > limit))
      case (_, _, Some(c)) =>
        for {
          o <- older(Some(c + 1), limit / 2)
          n <- newer(c, limit / 2)
        } yield (o.take(limit / 2).reverse ++ n.take(limit / 2), o.size > limit / 2, n.size > limit / 2)
      case (b, _, _) => older(b, limit).map(xs => (xs.take(limit).reverse, xs.size > limit, false))
    }
    q.flatMap(
      (
        xs,
        more,
        newerExists
      ) => views(xs, viewer).map(MessagePageDto(_, more, newerExists))
    )
  }

  /** スレッド（親と返信）。 */
  def thread(
    rootId: UUID,
    viewer: UUID
  ): IO[Option[(MessageDto, List[MessageDto], Boolean)]] = tx(
    findC(rootId).flatMap {
      case None => none.pure[ConnectionIO]
      case Some(root) =>
        for {
          rs <-
          (fr"select" ++ cols ++
            fr"from message m where m.root_id = $rootId and m.deleted_at is null order by m.seq").query[
            MessageRow
          ].to[List]
          vs <- views(root :: rs, viewer)
          fol <-
            sql"select following from thread_follow where root_id = $rootId and user_id = $viewer".query[Boolean].option
        } yield Some((vs.head, vs.tail, fol.getOrElse(false)))
    }
  )

  /** フォローしているスレッド（新しい返信の順）と、未読の数・最新の返信（2件）。問い合わせは3回にまとめる。 */
  def followedThreads(
    viewer: UUID,
    limit: Int
  ): IO[List[(MessageDto, ChannelBrief, Int, List[MessageDto])]] = tx(
    for {
      roots <-
      (fr"select" ++ cols ++
        fr""", c.name, c.kind,
                   (select count(*) from message r where r.root_id = m.id and r.seq > f.last_read_seq and r.deleted_at is null
                      and r.author_id is distinct from $viewer)::int,
                   array(select r.id from message r where r.root_id = m.id and r.deleted_at is null order by r.seq desc limit 2)
                 from thread_follow f join message m on m.id = f.root_id
                 join channel c on c.id = m.channel_id
                 join channel_member cm on cm.channel_id = c.id and cm.user_id = $viewer
                 where f.user_id = $viewer and f.following and m.reply_count > 0
                 order by m.last_reply_at desc nulls last limit $limit""").query[(
        MessageRow,
        String,
        String,
        Int,
        List[UUID]
      )].to[List]
      vs <- views(roots.map(_._1), viewer)
      lastIds = roots.flatMap(_._5)
      lasts <- if lastIds.isEmpty then List.empty[MessageRow].pure[ConnectionIO]
      else
        (fr"select" ++ cols ++ fr"from message m where m.id = any(${lastIds.toArray}) order by m.seq").query[
          MessageRow
        ].to[List]
      lv <- views(lasts, viewer).map(_.map(v => v.id -> v).toMap)
    } yield roots.zip(vs).map { case ((m, n, k, unread, ids), v) =>
      (v, ChannelBrief(m.channelId, n, k), unread, ids.reverse.flatMap(lv.get))
    }
  )

  /** 未読の返信があるフォロー中のスレッドの数。 */
  def unreadThreads(
    viewer: UUID
  ): IO[Int] = tx(
    sql"""select count(*) from thread_follow f join message m on m.id = f.root_id
          join channel_member cm on cm.channel_id = m.channel_id and cm.user_id = $viewer
          where f.user_id = $viewer and f.following and exists(select 1 from message r where r.root_id = f.root_id and r.seq > f.last_read_seq
            and r.deleted_at is null and r.author_id is distinct from $viewer)""".query[Int].unique
  )

  // ---------------------------------------------------------------- 投稿・編集・削除

  /** 投稿する。スレッドの返信なら親の返信数を増やし、作者と親の作者をフォローにする。新しいメッセージの行を返す。 */
  def insert(
    n: NewMessage
  ): IO[MessageRow] = tx {
    val id = UUID.randomUUID()
    for {
      _ <-
        sql"""insert into message (id, channel_id, root_id, also_channel, author_id, kind, via, body, body_text, props)
                 values ($id, ${n.channelId}, ${n.rootId}, ${n.alsoChannel}, ${n.authorId}, ${n.kind}, ${n.via}, ${n.body}, ${n.text}, ${n.props})"""
          .update.run
      names <- if n.attachmentIds.isEmpty then List.empty[String].pure[ConnectionIO]
      else
        sql"""update attachment set message_id = $id where id = any(${n.attachmentIds.toArray}) and channel_id = ${n.channelId}
                          and created_by = ${n.authorId} and message_id is null returning file_name""".query[
          String
        ].to[List]
      _ <- linkInline(id, n.channelId, n.authorId, n.body)
      _ <- if names.isEmpty then 0.pure[ConnectionIO]
      else
        sql"update message set body_text = trim(body_text || ' ' || ${names.mkString(" ")}) where id = $id".update.run
      _ <- n.rootId.traverse_ { r =>
        sql"update message set reply_count = reply_count + 1, last_reply_at = now() where id = $r".update.run *>
          follow(r, n.authorId, force = false) *>
          sql"""insert into thread_follow (root_id, user_id) select id, author_id from message where id = $r and author_id is not null
                     on conflict do nothing""".update.run
      }
      _ <- sql"update channel set last_message_at = now() where id = ${n.channelId}".update.run
      _ <- sql"update channel_member set hidden = false where channel_id = ${n.channelId} and hidden".update.run
      m <- findC(id).map(_.get)
      // チャンネルへの自分の投稿までは読んだことにする（スレッドの返信では変えない）
      _ <- if n.rootId.isDefined && !m.alsoChannel then 0.pure[ConnectionIO]
      else sql"""update channel_member set last_read_seq = greatest(last_read_seq, ${m.seq})
                      where channel_id = ${n.channelId} and user_id = ${n.authorId}""".update.run
      _ <- n.rootId.traverse_(r =>
        sql"update thread_follow set last_read_seq = ${m.seq} where root_id = $r and user_id = ${n.authorId}".update.run
      )
      _ <- event(n.channelId, EventKind.MessageCreated, Some(id), n.rootId, Some(n.authorId))
      _ <- n.rootId.traverse_(r => event(n.channelId, EventKind.MessageUpdated, Some(r), None, Some(n.authorId)))
    } yield m
  }

  def edit(
    id: UUID,
    body: Json,
    text: String,
    actor: UUID
  ): IO[Unit] = tx(
    for {
      m <- findC(id).map(_.get)
      _ <- m.authorId.traverse_(a => linkInline(id, m.channelId, a, body))
      files <- sql"select file_name from attachment where message_id = $id and not inline".query[String].to[List]
      t = (text :: files).mkString(" ").trim
      _ <- sql"update message set body = $body, body_text = $t, edited_at = now() where id = $id".update.run
      _ <- event(m.channelId, EventKind.MessageUpdated, Some(id), m.rootId, Some(actor))
    } yield ()
  )

  /** 本文に埋め込んだ画像（投稿した人がこのチャンネルにアップロードしたもの）をメッセージに結び付ける（見られる人を揃えるため）。 */
  private def linkInline(
    id: UUID,
    channelId: UUID,
    author: UUID,
    body: Json
  ): ConnectionIO[Int] = {
    val ids = RichText.fileIds(body).toList
    if ids.isEmpty then 0.pure[ConnectionIO]
    else
      sql"""update attachment set message_id = $id, inline = true where id = any(${ids.toArray}) and channel_id = $channelId
                and created_by = $author and message_id is null""".update.run
  }

  /** 削除（本文は残すが表示しない）。返信なら親の返信数を減らす。 */
  def delete(
    id: UUID,
    actor: UUID
  ): IO[Unit] = tx(
    for {
      m <- findC(id).map(_.get)
      _ <- sql"update message set deleted_at = now(), pinned_at = null where id = $id and deleted_at is null".update.run
      _ <- m.rootId.traverse_(r =>
        sql"update message set reply_count = greatest(reply_count - 1, 0) where id = $r".update.run
      )
      _ <- sql"delete from saved_message where message_id = $id".update.run
      _ <- sql"delete from notification where message_id = $id".update.run
      _ <- event(m.channelId, EventKind.MessageDeleted, Some(id), m.rootId, Some(actor))
      _ <- m.rootId.traverse_(r => event(m.channelId, EventKind.MessageUpdated, Some(r), None, Some(actor)))
    } yield ()
  )

  /** 付加情報を書き換える（会議の状態など）。 */
  def setProps(
    id: UUID,
    props: Json,
    actor: Option[UUID]
  ): IO[Unit] = tx(
    findC(id).flatMap(_.traverse_(m =>
      sql"update message set props = $props where id = $id".update.run *>
        event(m.channelId, EventKind.MessageUpdated, Some(id), m.rootId, actor)
    ))
  )

  // ---------------------------------------------------------------- リアクション・ピン留め・保存・フォロー

  def react(
    id: UUID,
    userId: UUID,
    emoji: String,
    on: Boolean
  ): IO[Unit] = tx(
    for {
      m <- findC(id).map(_.get)
      n <- if on then
        sql"insert into reaction (message_id, user_id, emoji) values ($id, $userId, $emoji) on conflict do nothing".update.run
      else sql"delete from reaction where message_id = $id and user_id = $userId and emoji = $emoji".update.run
      _ <- if n > 0 then event(m.channelId, EventKind.ReactionChanged, Some(id), m.rootId, Some(userId))
      else 0L.pure[ConnectionIO]
    } yield ()
  )

  def reactions(
    id: UUID
  ): IO[List[ReactionDto]] = tx(
    sql"""select r.emoji, u.id, u.name, u.avatar_url, u.is_bot from reaction r join app_user u on u.id = r.user_id
          where r.message_id = $id order by r.created_at""".query[(String, UserBrief)].to[List]
      .map(xs => xs.map(_._1).distinct.map(e => ReactionDto(e, xs.filter(_._1 == e).map(_._2))))
  )

  def pin(
    id: UUID,
    userId: UUID,
    on: Boolean
  ): IO[Unit] = tx(
    for {
      m <- findC(id).map(_.get)
      _ <- if on then
        sql"update message set pinned_at = now(), pinned_by = $userId where id = $id and pinned_at is null".update.run
      else sql"update message set pinned_at = null, pinned_by = null where id = $id".update.run
      _ <- event(m.channelId, EventKind.MessageUpdated, Some(id), m.rootId, Some(userId))
    } yield ()
  )

  def pinned(
    channelId: UUID,
    viewer: UUID
  ): IO[List[MessageDto]] = tx(
    (fr"select" ++ cols ++
      fr"from message m where m.channel_id = $channelId and m.pinned_at is not null and m.deleted_at is null order by m.pinned_at desc")
      .query[MessageRow].to[List].flatMap(views(_, viewer))
  )

  def save(
    id: UUID,
    userId: UUID,
    on: Boolean
  ): IO[Unit] = tx(
    if on then
      sql"insert into saved_message (user_id, message_id) values ($userId, $id) on conflict do nothing".update.run.void
    else sql"delete from saved_message where user_id = $userId and message_id = $id".update.run.void
  )

  /** 保存したメッセージのうち、今も見られるもの（一覧と件数で同じ条件）。 */
  private def savedFrom(
    userId: UUID
  ): Fragment =
    fr"""from saved_message s join message m on m.id = s.message_id join channel c on c.id = m.channel_id
       where s.user_id = $userId and m.deleted_at is null
         and (c.kind = 'public' or exists(select 1 from channel_member cm where cm.channel_id = c.id and cm.user_id = $userId))"""

  /** 保存したメッセージ（今も見られるチャンネルのものだけ）。 */
  def saved(
    userId: UUID,
    limit: Int
  ): IO[List[(MessageDto, ChannelBrief)]] = tx(
    (fr"select" ++ cols ++ fr", c.name, c.kind" ++ savedFrom(userId) ++
      fr"order by s.created_at desc limit $limit").query[(MessageRow, String, String)].to[List]
      .flatMap(rs =>
        views(rs.map(_._1), userId).map(_.zip(rs).map(
          (
            v,
            r
          ) => (v, ChannelBrief(r._1.channelId, r._2, r._3))
        ))
      )
  )

  def savedCount(
    userId: UUID
  ): IO[Int] = tx((fr"select count(*)::int" ++ savedFrom(userId)).query[Int].unique)

  /** スレッドをフォローする・やめる（force = false なら、やめている人は戻さない）。 */
  def follow(
    rootId: UUID,
    userId: UUID,
    force: Boolean = true,
    on: Boolean = true
  ): ConnectionIO[Int] =
    if force then
      sql"""insert into thread_follow (root_id, user_id, following) values ($rootId, $userId, $on)
            on conflict (root_id, user_id) do update set following = excluded.following""".update.run
    else sql"insert into thread_follow (root_id, user_id) values ($rootId, $userId) on conflict do nothing".update.run

  def setFollow(
    rootId: UUID,
    userId: UUID,
    on: Boolean
  ): IO[Unit] = tx(follow(rootId, userId, force = true, on = on).void)

  /** スレッドをフォローしている人（返信の通知の宛先）。 */
  def followers(
    rootId: UUID
  ): IO[List[UUID]] =
    tx(sql"select user_id from thread_follow where root_id = $rootId and following".query[UUID].to[List])

  def markThreadRead(
    rootId: UUID,
    userId: UUID
  ): IO[Unit] = tx(
    sql"""update thread_follow set last_read_seq = coalesce((select max(seq) from message where root_id = $rootId), 0)
          where root_id = $rootId and user_id = $userId""".update.run.void
  )

  /** チャンネルの最新の通し番号。 */
  def latestSeq(
    channelId: UUID
  ): IO[Long] = tx(sql"select coalesce(max(seq), 0) from message where channel_id = $channelId".query[Long].unique)

  /** スレッドの返信をしたことがある人・親の作者（返信の通知用）。 */
  def threadParticipants(
    rootId: UUID
  ): IO[List[UUID]] = tx(
    sql"select author_id from message where (id = $rootId or root_id = $rootId) and author_id is not null group by author_id".query[
      UUID
    ].to[List]
  )

  // ---------------------------------------------------------------- 添付ファイル

  def addAttachment(
    channelId: UUID,
    key: String,
    name: String,
    size: Long,
    contentType: String,
    sha: String,
    by: UUID
  ): IO[AttachmentRow] = tx {
    val id = UUID.randomUUID()
    sql"""insert into attachment (id, channel_id, s3_key, file_name, size, content_type, sha256, created_by)
          values ($id, $channelId, $key, $name, $size, $contentType, $sha, $by)
          returning id, channel_id, message_id, s3_key, file_name, size, content_type, created_by""".query[
      AttachmentRow
    ].unique
  }

  def attachment(
    id: UUID
  ): IO[Option[AttachmentRow]] = tx(
    sql"""select a.id, a.channel_id, a.message_id, a.s3_key, a.file_name, a.size, a.content_type, a.created_by from attachment a
          where a.id = $id and (a.message_id is null or exists(select 1 from message m where m.id = a.message_id and m.deleted_at is null))"""
      .query[AttachmentRow].option
  )

  /** 投稿されなかった添付（1日たったもの）。S3 から消すためにキーを返す。 */
  def orphanAttachments: IO[List[(UUID, String)]] = tx(
    sql"""delete from attachment where message_id is null and created_at < now() - interval '1 day'
          and not exists(select 1 from message m where m.body::text like '%' || attachment.id::text || '%')
          returning id, s3_key""".query[(UUID, String)].to[List]
  )

  // ---------------------------------------------------------------- 下書き

  private def rootCond(
    rootId: Option[UUID]
  ) = rootId.fold(fr"root_id is null")(r => fr"root_id = $r")

  def draft(
    userId: UUID,
    channelId: UUID,
    rootId: Option[UUID]
  ): IO[Option[DraftDto]] = tx(
    (fr"select body, updated_at from message_draft where user_id = $userId and channel_id = $channelId and" ++
      rootCond(rootId)).query[DraftDto].option
  )

  def saveDraft(
    userId: UUID,
    channelId: UUID,
    rootId: Option[UUID],
    body: Json
  ): IO[Unit] = tx(
    (fr"delete from message_draft where user_id = $userId and channel_id = $channelId and" ++
      rootCond(rootId)).update.run *>
      (if RichText.isBlank(body) then 0.pure[ConnectionIO]
       else
         sql"insert into message_draft (user_id, channel_id, root_id, body) values ($userId, $channelId, $rootId, $body)".update.run
      ).void
  )

  def deleteDraft(
    userId: UUID,
    channelId: UUID,
    rootId: Option[UUID]
  ): IO[Unit] = saveDraft(userId, channelId, rootId, RichText.empty)

  def drafts(
    userId: UUID
  ): IO[List[DraftListItemDto]] = tx(
    sql"""select c.id, c.name, c.kind, d.root_id, d.body, d.updated_at from message_draft d join channel c on c.id = d.channel_id
          where d.user_id = $userId order by d.updated_at desc"""
      .query[(UUID, String, String, Option[UUID], Json, Instant)].to[List]
      .map(_.map(r =>
        DraftListItemDto(ChannelBrief(r._1, r._2, r._3), r._4, r._5, RichText.toPlain(r._5).take(200), r._6)
      ))
  )

  // ---------------------------------------------------------------- 変更履歴（画面の同期）

  def event(
    channelId: UUID,
    kind: String,
    messageId: Option[UUID],
    rootId: Option[UUID],
    actor: Option[UUID],
    user: Option[UUID] = None
  )
    : ConnectionIO[Long] =
    sql"""insert into chat_event (channel_id, kind, message_id, root_id, actor_id, user_id)
          values ($channelId, $kind, $messageId, $rootId, $actor, $user) returning id""".query[Long].unique

  def eventIO(
    channelId: UUID,
    kind: String,
    messageId: Option[UUID] = None,
    rootId: Option[UUID] = None,
    actor: Option[UUID] = None,
    user: Option[UUID] = None
  ): IO[Long] = tx(event(channelId, kind, messageId, rootId, actor, user))

  def latestEvent: IO[Long] = tx(sql"select coalesce(max(id), 0) from chat_event".query[Long].unique)

  /** 新しい変更（ノードの配信用。チャンネルと、メンバーの変更の対象）。 */
  def eventsAfter(
    id: Long
  ): IO[List[(Long, UUID, Option[UUID])]] = tx(
    sql"select id, channel_id, user_id from chat_event where id > $id order by id limit 1000".query[(
      Long,
      UUID,
      Option[UUID]
    )].to[List]
  )

  /** 自分に関係のある変更（参加しているチャンネル・見ている公開チャンネル・自分のメンバーの変更）。 */
  def changes(
    userId: UUID,
    since: Long,
    watch: Option[UUID]
  ): IO[ChangesDto] = tx(
    for {
      evs <- sql"""select e.id, e.channel_id, e.kind, e.message_id, e.root_id, e.actor_id, e.user_id from chat_event e
                   where e.id > $since and (e.user_id = $userId
                     or exists(select 1 from channel_member cm where cm.channel_id = e.channel_id and cm.user_id = $userId)
                     or (e.channel_id = $watch and exists(select 1 from channel c where c.id = e.channel_id and c.kind = 'public')))
                   order by e.id limit 500""".query[ChatEventDto].to[List]
      latest <- if evs.size >= 500 then evs.last.id.pure[ConnectionIO]
      else sql"select coalesce(max(id), 0) from chat_event".query[Long].unique
    } yield ChangesDto(evs, math.max(latest, since))
  )

  def purgeEvents(
    days: Int
  ): IO[Int] = tx(sql"delete from chat_event where at < now() - make_interval(days => $days)".update.run)

  // ---------------------------------------------------------------- 連携サービス向けの一括参照

  /** メッセージの概要（見られるものだけ）。 */
  def lookup(
    ids: List[UUID],
    viewer: UUID
  ): IO[List[(UUID, ChannelBrief, Option[String], String)]] = tx(
    sql"""select m.id, c.id, c.name, c.kind, u.name, m.body_text from message m join channel c on c.id = m.channel_id
          left join app_user u on u.id = m.author_id
          where m.id = any(${ids.toArray}) and m.deleted_at is null
            and (c.kind = 'public' or exists(select 1 from channel_member cm where cm.channel_id = c.id and cm.user_id = $viewer))"""
      .query[(UUID, UUID, String, String, Option[String], String)].to[List]
      .map(_.map(r => (r._1, ChannelBrief(r._2, r._3, r._4), r._5, r._6.take(200))))
  )

  /** リマインダー・定期メッセージで表示するメッセージの概要。 */
  def ref(
    id: UUID
  ): ConnectionIO[Option[MessageRefDto]] =
    sql"""select m.id, c.id, c.name, c.kind, u.id, u.name, u.avatar_url, u.is_bot, m.body_text, m.root_id from message m
          join channel c on c.id = m.channel_id left join app_user u on u.id = m.author_id where m.id = $id"""
      .query[(
        UUID,
        UUID,
        String,
        String,
        Option[UUID],
        Option[String],
        Option[String],
        Option[Boolean],
        String,
        Option[UUID]
      )].option
      .map(_.map(r =>
        MessageRefDto(
          r._1,
          ChannelBrief(r._2, r._3, r._4),
          (r._5, r._6).mapN(
            (
              i,
              n
            ) => UserBrief(i, n, r._7, r._8.getOrElse(false))
          ),
          r._9.take(200),
          r._10
        )
      ))
}

/** 添付ファイルの URL。 */
object Files {
  def url(
    id: UUID,
    name: String
  ): String = s"/api/files/$id/${java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
}
