package stui.api

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import fs2.Stream

/** Mattermost の DB を読む（読むだけ）。表・列の名前は Mattermost の PostgreSQL のもの（小文字）。消したもの（deleteat）は読まない。 */
final class MattermostSource(xa: Transactor[IO]) {
  private val chunk = 2000

  def teams: IO[List[MmTeam]] =
    sql"select id, name, displayname from teams where deleteat = 0 order by name".query[MmTeam].to[List].transact(xa)

  def users: IO[List[MmUser]] =
    sql"""select u.id, u.username, coalesce(u.email, ''), coalesce(u.firstname, ''), coalesce(u.lastname, ''), coalesce(u.nickname, ''),
                 u.createat, u.deleteat, exists(select 1 from bots b where b.userid = u.id)
          from users u order by u.createat""".query[MmUser].to[List].transact(xa)

  /** チャンネル（teams が空ならすべてのチーム）。DM・グループ DM はチームに属さないので、いつも読む。 */
  def channels(teams: List[String]): IO[List[MmChannel]] = {
    val inTeams = teams.toNel.fold(fr"true")(nel => Fragments.in(fr"teamid", nel) ++ fr"or teamid = ''")
    (fr"""select id, teamid, type, coalesce(displayname, name), coalesce(header, ''), coalesce(purpose, ''), coalesce(creatorid, ''),
                 createat, deleteat
          from channels where""" ++ inTeams ++ fr"order by createat").query[MmChannel].to[List].transact(xa)
  }

  def members: IO[List[MmMember]] =
    sql"select channelid, userid, coalesce(schemeadmin, false), lastviewedat from channelmembers".query[
      MmMember
    ].to[List].transact(xa)

  /** 投稿（古い順. スレッドの親が返信より先に来る）。編集前の版（originalid のあるもの）は消したものとして持っているので含まれない。 */
  def posts: Stream[IO, MmPost] =
    sql"""select id, channelid, userid, coalesce(rootid, ''), coalesce(message, ''), coalesce(type, ''), coalesce(props::text, '{}'),
                 createat, coalesce(editat, 0), coalesce(ispinned, false)
          from posts where deleteat = 0 order by createat, id""".query[MmPost].streamWithChunkSize(chunk).transact(xa)

  def reactions: Stream[IO, MmReaction] =
    sql"select postid, userid, emojiname, createat from reactions where deleteat = 0 order by createat"
      .query[MmReaction].streamWithChunkSize(chunk).transact(xa)

  def files: Stream[IO, MmFile] =
    sql"""select id, postid, path, name, size, coalesce(mimetype, ''), coalesce(creatorid, '') from fileinfo
          where deleteat = 0 and coalesce(postid, '') <> '' order by createat""".query[MmFile].streamWithChunkSize(
      chunk
    ).transact(xa)

  def emoji: IO[List[MmEmoji]] =
    sql"select id, name, coalesce(creatorid, '') from emoji where deleteat = 0 order by createat".query[
      MmEmoji
    ].to[List].transact(xa)

  /** 取り込む投稿の数（確かめるだけのとき）。 */
  def postCount: IO[Long] =
    sql"select count(*) from posts where deleteat = 0 and left(coalesce(type, ''), 7) <> 'system_'".query[
      Long
    ].unique.transact(xa)
}
