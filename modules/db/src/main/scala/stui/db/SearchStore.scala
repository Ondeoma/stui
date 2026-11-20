package stui.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import stui.core.*
import stui.shared.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/**
 * メッセージの検索（PGroonga）。かな・全角半角の揺れはインデックスのノーマライザで吸収し、
 * 完全な一致が少なければ、タイプミスを許した一致（fuzzy）を下に足す。
 */
final class SearchStore(xa: Transactor[IO], messages: MessageStore) extends TxStore(xa) {

  private val FuzzyRatio = 0.34f

  private val cols =
    fr"""m.id, m.seq, m.channel_id, m.root_id, m.also_channel, m.author_id, m.kind, m.via, m.body, m.body_text, m.props,
                          m.reply_count, m.last_reply_at, m.pinned_at, m.created_at, m.edited_at, m.deleted_at"""

  /** 名前（me は自分）からユーザーの ID を探す。 */
  private def userIds(v: String, viewer: UUID): ConnectionIO[List[UUID]] =
    if v.equalsIgnoreCase("me") || v == "自分" then List(viewer).pure[ConnectionIO]
    else
      uuidOpt(v) match {
        case Some(id) => List(id).pure[ConnectionIO]
        case None =>
          sql"""select id from app_user where lower(name) = lower($v) or lower(email) = lower($v) or split_part(lower(email), '@', 1) = lower($v)
                union select id from app_user where name ilike ${"%" + v + "%"} limit 20""".query[UUID].to[List]
      }

  /** in: の値からチャンネルの ID（#名前、@名前 は DM）。 */
  private def channelIds(v: String, viewer: UUID): ConnectionIO[List[UUID]] =
    uuidOpt(v) match {
      case Some(id) => List(id).pure[ConnectionIO]
      case None if v.startsWith("@") =>
        userIds(v.drop(1), viewer).flatMap(us =>
          sql"""select c.id from channel c join channel_member a on a.channel_id = c.id and a.user_id = $viewer
                join channel_member b on b.channel_id = c.id and b.user_id = any(${us.toArray})
                where c.kind in ('dm', 'group')""".query[UUID].to[List]
        )
      case None =>
        sql"select id from channel where kind in ('public', 'private') and (lower(name) = lower($v) or name ilike ${"%" +
            v + "%"})".query[UUID].to[List]
    }

  private def mentionCond(ids: List[UUID]): Fragment =
    Sql.or(ids.map(id =>
      fr"""jsonb_path_exists(m.body, '$$.** ? (@.type == "mention" && @.attrs.id == $$id)', jsonb_build_object('id', ${id.toString}))"""
    ))

  private def hasCond(v: String, viewer: UUID): Fragment = v match {
    case "link" => fr"""(jsonb_path_exists(m.body, '$$.** ? (@.type == "link")') or m.body_text ~ 'https?://')"""
    case "file" => fr"exists(select 1 from attachment a where a.message_id = m.id)"
    case "image" => fr"""(exists(select 1 from attachment a where a.message_id = m.id and a.content_type like 'image/%')
                           or jsonb_path_exists(m.body, '$$.** ? (@.type == "image")'))"""
    case "reaction" => fr"exists(select 1 from reaction r where r.message_id = m.id)"
    case "pin" => fr"m.pinned_at is not null"
    case "meeting" => fr"m.props->'meeting' is not null"
    case "thread" => fr"m.reply_count > 0"
    case _ => fr"""jsonb_path_exists(m.body, '$$.** ? (@.type == "mention")')"""
  }

  private def isCond(v: String, viewer: UUID): Fragment = v match {
    case "thread" => fr"(m.root_id is not null or m.reply_count > 0)"
    case "reply" => fr"m.root_id is not null"
    case "pinned" => fr"m.pinned_at is not null"
    case "saved" => fr"exists(select 1 from saved_message s where s.message_id = m.id and s.user_id = $viewer)"
    case "bot" => fr"m.kind = 'bot'"
    case "dm" => fr"c.kind in ('dm', 'group')"
    case _ =>
      fr"m.seq > coalesce((select cm.last_read_seq from channel_member cm where cm.channel_id = c.id and cm.user_id = $viewer), 0)"
  }

  private def dayStart(d: LocalDate, z: ZoneId): Instant = d.atStartOfDay(z).toInstant

  /** 絞り込みの条件（キーごとの値は OR、キーどうしは AND。否定は NOT）。 */
  private def filterConds(q: SearchQuery.Query,
                          viewer: UUID,
                          zone: ZoneId): ConnectionIO[Either[String, List[Fragment]]] = {
    val today = LocalDate.now(zone)
    q.filters.traverse { f =>
      val cond: ConnectionIO[Either[String, Fragment]] = f.key match {
        case "in" => channelIds(f.value, viewer).map(ids => Right(fr"m.channel_id = any(${ids.toArray})"))
        case "from" => userIds(f.value, viewer).map(ids => Right(fr"m.author_id = any(${ids.toArray})"))
        case "with" | "mentions" =>
          userIds(f.value, viewer).map(ids => Right(if ids.isEmpty then fr"false" else mentionCond(ids)))
        case "has" => Right(hasCond(f.value, viewer)).pure[ConnectionIO]
        case "is" => Right(isCond(f.value, viewer)).pure[ConnectionIO]
        case "before" => SearchQuery.date(f.value, today).fold(Left(s"日付が正しくありません: ${f.value}"))(d =>
            Right(fr"m.created_at < ${dayStart(d, zone)}")
          ).pure[ConnectionIO]
        case "after" => SearchQuery.date(f.value, today).fold(Left(s"日付が正しくありません: ${f.value}"))(d =>
            Right(fr"m.created_at >= ${dayStart(d.plusDays(1), zone)}")
          ).pure[ConnectionIO]
        case "on" => SearchQuery.date(f.value, today).fold(Left(s"日付が正しくありません: ${f.value}"))(d =>
            Right(fr"m.created_at >= ${dayStart(d, zone)} and m.created_at < ${dayStart(d.plusDays(1), zone)}")
          ).pure[ConnectionIO]
        case "during" => SearchQuery.period(f.value, today).fold(Left(s"期間が正しくありません: ${f.value}"))((a, b) =>
            Right(fr"m.created_at >= ${dayStart(a, zone)} and m.created_at < ${dayStart(b, zone)}")
          ).pure[ConnectionIO]
        case _ => Right(fr"true").pure[ConnectionIO] // order
      }
      cond.map(_.map(c => (f.key, f.negate, c)))
    }.map(_.sequence.map { cs =>
      cs.filterNot(_._1 == "order").groupBy(x => (x._1, x._2)).toList.map { case ((_, neg), xs) =>
        val any = Sql.or(xs.map(_._3))
        if neg then fr"not (" ++ any ++ fr")" else any
      }
    })
  }

  /** 検索する。結果はスコア（またはは新しい順）で並べ、抜粋を付ける。 */
  def search(viewer: UUID,
             zone: ZoneId,
             q: SearchQuery.Query,
             limit: Int,
             offset: Int): IO[Either[String, (List[SearchHitDto], Int)]] = tx {
    filterConds(q, viewer, zone).flatMap {
      case Left(e) => (Left(e): Either[String, (List[SearchHitDto], Int)]).pure[ConnectionIO]
      case Right(conds) =>
        val hasText = q.text.nonEmpty
        val text = q.text.map(SearchQuery.phrase).mkString(" ")
        val negs = q.negText.map(w => fr"not (m.body_text &@~ ${SearchQuery.phrase(w)})")
        val order = q.values("order").headOption.getOrElse(if hasText then "relevance" else "new")
        def textCond(fuzzy: Boolean): Fragment = {
          val ratio = if fuzzy then FuzzyRatio else 0.0f
          fr"m.body_text &@~ pgroonga_condition($text, index_name => 'message_search_idx', fuzzy_max_distance_ratio => $ratio::real)"
        }
        def run(fuzzy: Boolean, exclude: List[UUID], lim: Int, off: Int) = {
          val where = List(
            Some(fr"m.deleted_at is null"),
            Some(
              fr"(c.kind = 'public' or exists(select 1 from channel_member cm where cm.channel_id = c.id and cm.user_id = $viewer))"
            ),
            Option.when(hasText)(textCond(fuzzy)),
            Option.when(exclude.nonEmpty)(fr"not (m.id = any(${exclude.toArray}))")
          ).flatten ++ conds ++ negs
          val snippet =
            if hasText then fr"(pgroonga_snippet_html(m.body_text, pgroonga_query_extract_keywords($text), 200))[1]"
            else fr"null::text"
          val orderBy = order match {
            case "old" => fr"order by m.seq"
            case "relevance" | "score" if hasText => fr"order by pgroonga_score(m.tableoid, m.ctid) desc, m.seq desc"
            case _ => fr"order by m.seq desc"
          }
          (fr"select" ++ cols ++ fr", c.name, c.kind," ++ snippet ++
            fr", count(*) over () from message m join channel c on c.id = m.channel_id" ++
            Sql.whereAnd(where) ++ orderBy ++ fr"limit $lim offset $off")
            .query[(MessageRow, String, String, Option[String], Long)].to[List]
        }
        for {
          exact <- run(fuzzy = false, Nil, limit, offset)
          total = exact.headOption.map(_._5.toInt).getOrElse(0)
          fuzzy <- if hasText && total < 5 && offset == 0 && limit > exact.size then
            run(fuzzy = true, exact.map(_._1.id), limit - exact.size, 0)
          else List.empty.pure[ConnectionIO]
          all = exact.map(_ -> false) ++ fuzzy.map(_ -> true)
          views <- messages.views(all.map(_._1._1), viewer)
        } yield Right((
          views.zip(all).map { case (v, ((m, n, k, sn, _), fz)) =>
            SearchHitDto(v, ChannelBrief(m.channelId, n, k), sn, fz)
          },
          total + fuzzy.headOption.map(_._5.toInt).getOrElse(0)
        ))
    }
  }

  /** ファイル名の検索（見られるチャンネルのもの）。 */
  def files(viewer: UUID, text: List[String], limit: Int): IO[List[FileHitDto]] = tx {
    val conds = text.map(w => fr"(a.file_name &@~ ${SearchQuery.phrase(w)} or a.file_name ilike ${"%" + w + "%"})")
    (fr"""select a.id, a.file_name, a.size, a.content_type, c.id, c.name, c.kind, a.message_id, u.id, u.name, u.avatar_url, u.is_bot, a.created_at
          from attachment a join channel c on c.id = a.channel_id left join app_user u on u.id = a.created_by
          left join message m on m.id = a.message_id
          where a.message_id is not null and m.deleted_at is null
            and (c.kind = 'public' or exists(select 1 from channel_member cm where cm.channel_id = c.id and cm.user_id = $viewer))""" ++
      (if conds.isEmpty then Fragment.empty else fr"and" ++ Sql.and(conds)) ++
      fr"order by a.created_at desc limit $limit")
      .query[(
        UUID,
        String,
        Long,
        String,
        UUID,
        String,
        String,
        Option[UUID],
        Option[UUID],
        Option[String],
        Option[String],
        Option[Boolean],
        Instant
      )]
      .map(r =>
        FileHitDto(
          AttachmentDto(r._1, r._2, r._3, r._4, Files.url(r._1, r._2)),
          ChannelBrief(r._5, r._6, r._7),
          r._8,
          (r._9, r._10).mapN((i, n) => UserBrief(i, n, r._11, r._12.getOrElse(false))),
          r._13
        )
      ).to[List]
  }
}
