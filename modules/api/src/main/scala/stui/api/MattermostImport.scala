package stui.api

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import fs2.Stream
import fs2.io.file.Path as FPath
import io.circe.Json
import io.circe.parser.parse
import stui.core.*
import stui.db.*
import stui.storage.BlobStore

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

// ------------------------------------------------------------------ 取り込み元（Mattermost の PostgreSQL. ダンプを戻したもの）

final case class MmTeam(id: String, name: String, displayName: String)
final case class MmUser(id: String, username: String, email: String, first: String, last: String, nickname: String, createAt: Long,
    deleteAt: Long, isBot: Boolean)
final case class MmChannel(id: String, teamId: String, tpe: String, displayName: String, header: String, purpose: String, creatorId: String,
    createAt: Long, deleteAt: Long)
final case class MmMember(channelId: String, userId: String, admin: Boolean, lastViewedAt: Long)
final case class MmPost(id: String, channelId: String, userId: String, rootId: String, message: String, tpe: String, props: String,
    createAt: Long, editAt: Long, pinned: Boolean)
final case class MmReaction(postId: String, userId: String, emoji: String, createAt: Long)
final case class MmFile(id: String, postId: String, path: String, name: String, size: Long, mime: String, creatorId: String)
final case class MmEmoji(id: String, name: String, creatorId: String)

/** Mattermost の DB を読む（読むだけ）。表・列の名前は Mattermost の PostgreSQL のもの（小文字）。消したもの（deleteat）は読まない。 */
final class MattermostSource(xa: Transactor[IO]):
  private val chunk = 2000

  def teams: IO[List[MmTeam]] =
    sql"select id, name, displayname from teams where deleteat = 0 order by name".query[MmTeam].to[List].transact(xa)

  def users: IO[List[MmUser]] =
    sql"""select u.id, u.username, coalesce(u.email, ''), coalesce(u.firstname, ''), coalesce(u.lastname, ''), coalesce(u.nickname, ''),
                 u.createat, u.deleteat, exists(select 1 from bots b where b.userid = u.id)
          from users u order by u.createat""".query[MmUser].to[List].transact(xa)

  /** チャンネル（teams が空ならすべてのチーム）。DM・グループ DM はチームに属さないので、いつも読む。 */
  def channels(teams: List[String]): IO[List[MmChannel]] =
    val inTeams = teams.toNel.fold(fr"true")(nel => Fragments.in(fr"teamid", nel) ++ fr"or teamid = ''")
    (fr"""select id, teamid, type, coalesce(displayname, name), coalesce(header, ''), coalesce(purpose, ''), coalesce(creatorid, ''),
                 createat, deleteat
          from channels where""" ++ inTeams ++ fr"order by createat").query[MmChannel].to[List].transact(xa)

  def members: IO[List[MmMember]] =
    sql"select channelid, userid, coalesce(schemeadmin, false), lastviewedat from channelmembers".query[MmMember].to[List].transact(xa)

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
          where deleteat = 0 and coalesce(postid, '') <> '' order by createat""".query[MmFile].streamWithChunkSize(chunk).transact(xa)

  def emoji: IO[List[MmEmoji]] =
    sql"select id, name, coalesce(creatorid, '') from emoji where deleteat = 0 order by createat".query[MmEmoji].to[List].transact(xa)

  /** 取り込む投稿の数（確かめるだけのとき）。 */
  def postCount: IO[Long] =
    sql"select count(*) from posts where deleteat = 0 and left(coalesce(type, ''), 7) <> 'system_'".query[Long].unique.transact(xa)

// ------------------------------------------------------------------ 取り込み

/** teams: 取り込むチーム（名前. 空ならすべて）。filesDir: Mattermost のファイルの置き場所（FileSettings.Directory. 添付・カスタム絵文字）。 */
final case class MattermostImportOptions(teams: List[String] = Nil, filesDir: Option[Path] = None, dryRun: Boolean = false)

/** 取り込んだ数（skipped は取り込み済み・取り込めなかったもの）。 */
final case class MattermostImportReport(users: Int = 0, linkedUsers: Int = 0, channels: Int = 0, members: Int = 0, posts: Int = 0,
    reactions: Int = 0, files: Int = 0, missingFiles: Int = 0, emoji: Int = 0, skipped: Int = 0)

/**
 * Mattermost から取り込む。ユーザー → チャンネル・メンバー → 投稿（スレッド・ピン留め）→ リアクション → 添付 → カスタム絵文字の順。
 * 取り込んだものは import_map に記録し、もう一度実行しても二重に作らない（あとから増えた投稿だけを足す）。
 * チームは stui にないので、チームのチャンネルは同じ一覧に並べる（名前がぶつかったら「名前 (チーム名)」にする）。
 */
final class MattermostImporter(src: MattermostSource, store: ImportStore, blobs: Option[BlobStore], log: String => IO[Unit]):

  private def at(ms: Long): Instant = Instant.ofEpochMilli(ms)
  private val batch = 500

  def run(opts: MattermostImportOptions): IO[MattermostImportReport] =
    for
      allTeams <- src.teams
      unknown   = opts.teams.filterNot(t => allTeams.exists(_.name == t))
      _        <- IO.raiseWhen(unknown.nonEmpty)(new IllegalArgumentException(
                    s"チームが見つかりません: ${unknown.mkString(", ")}（あるのは ${allTeams.map(_.name).mkString(", ")}）"))
      teams     = allTeams.filter(t => opts.teams.isEmpty || opts.teams.contains(t.name))
      report   <- if opts.dryRun then dryRun(teams) else importAll(teams, opts)
    yield report

  /** 書き込まずに、取り込む数を数える。 */
  private def dryRun(teams: List[MmTeam]): IO[MattermostImportReport] =
    for
      users    <- src.users
      channels <- src.channels(teams.map(_.id))
      posts    <- src.postCount
      _        <- log(s"チーム: ${teams.map(t => s"${t.displayName}（${t.name}）").mkString("、")}")
      _        <- channels.groupBy(_.tpe).toList.sortBy(_._1).traverse_((k, cs) => log(s"チャンネル（$k）: ${cs.size}"))
      _        <- log(s"投稿（チーム外のものも含む）: $posts")
    yield MattermostImportReport(users = users.size, channels = channels.size, posts = posts.toInt)

  private def importAll(teams: List[MmTeam], opts: MattermostImportOptions): IO[MattermostImportReport] =
    for
      u                    <- importUsers
      (users, mmUsers, r1)  = u
      c                    <- importChannels(teams, users)
      (channels, seen, r2)  = c
      r3                   <- importPosts(channels, users, mmUsers, seen)
      r4                   <- importReactions(users)
      r5                   <- opts.filesDir.fold(IO.pure(MattermostImportReport()))(dir => (importFiles(dir, users), importEmoji(dir, users)).mapN(_ |+| _))
      _                    <- log("スレッド・既読の位置・チャンネルの最後のメッセージを合わせています") *> store.finish
    yield r1 |+| r2 |+| r3 |+| r4 |+| r5

  // ---------------------------------------------------------------- ユーザー

  /** 同じメールアドレスの人がいればその人に結び付け、いなければ作る（最初のログインで引き継ぐ）。 */
  private def importUsers: IO[(Map[String, UUID], List[MmUser], MattermostImportReport)] =
    for
      done  <- store.mapped("user")
      all   <- src.users
      fresh  = all.filterNot(u => done.contains(u.id))
      res   <- fresh.traverse { u =>
                 val email = Option(u.email.trim).filter(e => e.contains("@") && !u.isBot)
                 email.flatTraverse(store.userByEmail).flatMap {
                   case Some(id) => store.map("user", u.id, id).as((u.id, id, true))
                   case None =>
                     store.insertUser(u.id, ImportUser(Mattermost.SubPrefix + u.id, Mattermost.displayName(u.username, u.first, u.last, u.nickname),
                       email, u.isBot, u.deleteAt > 0, at(u.createAt))).map((u.id, _, false))
                 }
               }
      _     <- log(s"ユーザー: 新しく ${res.count(!_._3)}・既存の人に結び付け ${res.count(_._3)}・取り込み済み ${done.size}")
    yield (done ++ res.map((s, t, _) => s -> t), all,
      MattermostImportReport(users = res.count(!_._3), linkedUsers = res.count(_._3), skipped = done.size))

  // ---------------------------------------------------------------- チャンネル・メンバー

  /** 新しく加えたメンバーと、その人が取り込み元で最後にチャンネルを見た時刻（投稿のあとで既読の位置を合わせる）。 */
  private type Seen = List[(UUID, UUID, Instant)]

  private def importChannels(teams: List[MmTeam], users: Map[String, UUID]): IO[(Map[String, UUID], Seen, MattermostImportReport)] =
    for
      done     <- store.mapped("channel")
      channels <- src.channels(teams.map(_.id))
      members  <- src.members.map(_.groupBy(_.channelId))
      teamName  = teams.map(t => t.id -> t.displayName).toMap
      created  <- channels.filterNot(c => done.contains(c.id)).flatTraverse { c =>
                    Mattermost.channelKind(c.tpe) match
                      case None => IO.pure(Nil)
                      case Some(kind @ ("dm" | "group")) =>
                        val ids = members.getOrElse(c.id, Nil).flatMap(m => users.get(m.userId)).distinct
                        if ids.isEmpty then IO.pure(Nil)
                        else store.dmChannel(c.id, DmKey.of(ids), kind, at(c.createAt)).map(id => List(c.id -> id))
                      case Some(kind) =>
                        uniqueName(c.displayName.trim.take(80), teamName.get(c.teamId)).flatMap(name =>
                          store.insertChannel(c.id, ImportChannel(name, kind, c.header.take(250), c.purpose.take(250), c.deleteAt > 0,
                            users.get(c.creatorId), at(c.createAt))).map(id => List(c.id -> id)))
                  }
      all       = done ++ created
      // メンバー（取り込み済みのチャンネルにも、あとから加わった人を足す）
      rows      = channels.flatMap(c => all.get(c.id).toList.flatMap(cid => members.getOrElse(c.id, Nil).flatMap(m =>
                    users.get(m.userId).map(uid => (cid, uid, m.admin, at(m.lastViewedAt))))))
      added    <- rows.grouped(batch).toList.flatTraverse(g => store.addMembers(g.map((c, u, a, _) => (c, u, a))))
      seen      = rows.map((c, u, _, t) => (c, u) -> t).toMap
      _        <- log(s"チャンネル: 新しく ${created.size}・取り込み済み ${done.size}・メンバーを ${added.size} 人加えた")
    yield (all, added.flatMap((c, u) => seen.get((c, u)).map((c, u, _))),
      MattermostImportReport(channels = created.size, members = added.size, skipped = done.size))

  /** 公開・非公開のチャンネルの名前は重複できないので、ぶつかったら「名前 (チーム名)」「名前 (2)」… にする。 */
  private def uniqueName(base: String, team: Option[String]): IO[String] =
    val name0 = if base.isEmpty then "mattermost" else base
    val candidates = LazyList(name0) ++ team.map(t => s"$name0 ($t)").to(LazyList) ++ LazyList.from(2).map(n => s"$name0 ($n)")
    def go(cs: LazyList[String]): IO[String] = store.channelNameTaken(cs.head).ifM(go(cs.tail), IO.pure(cs.head))
    go(candidates)

  // ---------------------------------------------------------------- 投稿

  private def importPosts(channels: Map[String, UUID], users: Map[String, UUID], mmUsers: List[MmUser], seen: Seen)
      : IO[MattermostImportReport] =
    for
      names   <- store.userNames(users.values.toList.distinct)
      byName   = mmUsers.flatMap(u => users.get(u.id).map(id => u.username.toLowerCase -> (id, names.getOrElse(id, u.username)))).toMap
      done    <- store.mapped("post")
      posts   <- Ref.of[IO, Map[String, UUID]](done)
      count   <- Ref.of[IO, (Int, Int)]((0, 0))
      _       <- src.posts.chunkN(batch).evalMap { chunk =>
                   posts.get.flatMap { known =>
                     val (rows, ids) = chunk.toList.foldLeft((List.empty[(String, ImportMessage)], known)) { case ((acc, ids), p) =>
                       convert(p, channels, users, byName, ids).fold((acc, ids))(m => (acc :+ (p.id -> m), ids + (p.id -> m.id)))
                     }
                     val skipped = chunk.size - rows.size
                     store.insertMessages(rows) *> posts.set(ids) *>
                       count.updateAndGet((n, s) => (n + rows.size, s + skipped)).flatMap((n, _) =>
                         log(s"投稿: $n 件").whenA(rows.nonEmpty && (n / 10000) != ((n - rows.size) / 10000)))
                   }
                 }.compile.drain
      ns      <- count.get
      (n, s)   = ns
      _       <- store.setLastRead(seen)
      _       <- log(s"投稿: 新しく $n・取り込み済み・取り込まないもの $s")
    yield MattermostImportReport(posts = n, skipped = s)

  /** 取り込む投稿を stui のメッセージにする（取り込み済み・チャンネルを取り込まないもの・システムの投稿は None）。 */
  private def convert(p: MmPost, channels: Map[String, UUID], users: Map[String, UUID], byName: Map[String, (UUID, String)],
      known: Map[String, UUID]): Option[ImportMessage] =
    if known.contains(p.id) || !Mattermost.importable(p.tpe) then None
    else channels.get(p.channelId).map { cid =>
      val props   = parse(p.props).getOrElse(Json.obj())
      val c       = props.hcursor
      val webhook = c.get[String]("from_webhook").contains("true") || c.get[Boolean]("from_webhook").contains(true)
      val body    = Mattermost.body(p.message, props, byName.get)
      ImportMessage(UUID.randomUUID(), cid, Option(p.rootId).filter(_.nonEmpty).flatMap(known.get), users.get(p.userId),
        if webhook then "bot" else "user", body, RichText.toPlain(body),
        if webhook then Json.obj("username" -> c.get[String]("override_username").toOption.fold(Json.Null)(Json.fromString),
          "iconUrl" -> c.get[String]("override_icon_url").toOption.filter(_.startsWith("https://")).fold(Json.Null)(Json.fromString)).dropNullValues
        else Json.obj(),
        at(p.createAt), Option(p.editAt).filter(_ > 0).map(at), p.pinned)
    }

  // ---------------------------------------------------------------- リアクション

  private def importReactions(users: Map[String, UUID]): IO[MattermostImportReport] =
    for
      posts <- store.mapped("post")
      n     <- src.reactions.chunkN(batch).evalMap { chunk =>
                 store.addReactions(chunk.toList.flatMap(r =>
                   (posts.get(r.postId), users.get(r.userId), Mattermost.reaction(r.emoji)).mapN((m, u, e) => (m, u, e, at(r.createAt)))))
               }.compile.foldMonoid
      _     <- log(s"リアクション: 新しく $n")
    yield MattermostImportReport(reactions = n)

  // ---------------------------------------------------------------- 添付ファイル・カスタム絵文字（ファイルの置き場所を指定したとき）

  private def importFiles(dir: Path, users: Map[String, UUID]): IO[MattermostImportReport] = blobs match
    case None => log("添付ファイル: S3 が未設定のため取り込みません").as(MattermostImportReport())
    case Some(bs) =>
      for
        posts <- store.mapped("post")
        done  <- store.mapped("file")
        res   <- src.files.filter(f => !done.contains(f.id)).evalMap { f =>
                   val file = dir.resolve(f.path).normalize()
                   (posts.get(f.postId), Option.when(file.startsWith(dir.normalize()) && Files.isRegularFile(file))(file)) match
                     case (Some(mid), Some(path)) =>
                       store.messageInfo(mid).flatMap(_.traverse { (cid, created) =>
                         val key  = s"channels/$cid/${UUID.randomUUID()}"
                         val mime = Option(f.mime.takeWhile(_ != ';').trim.toLowerCase).filter(_.nonEmpty).getOrElse("application/octet-stream") // charset などは落とす
                         bs.put(key, FPath.fromNioPath(path), mime) *> BlobStore.sha256Hex(FPath.fromNioPath(path)).flatMap(sha =>
                           store.addAttachment(f.id, mid, cid, key, f.name.take(255), Files.size(path), mime, sha, users.get(f.creatorId), created))
                       }).map(r => if r.isDefined then (1, 0) else (0, 1))
                     case (Some(_), None) => IO.pure((0, 1))
                     case _               => IO.pure((0, 0))
                 }.compile.foldMonoid
        _     <- log(s"添付ファイル: 新しく ${res._1}・見つからない ${res._2}")
      yield MattermostImportReport(files = res._1, missingFiles = res._2)

  private def importEmoji(dir: Path, users: Map[String, UUID]): IO[MattermostImportReport] = blobs match
    case None => IO.pure(MattermostImportReport())
    case Some(bs) =>
      for
        done <- store.mapped("emoji")
        all  <- src.emoji
        n    <- all.filter(e => !done.contains(e.id) && e.name.matches("^[a-z0-9_+-]{2,32}$")).traverse { e =>
                  val file = dir.resolve("emoji").resolve(e.id).resolve("image")
                  imageType(file) match
                    case None => IO.pure(0)
                    case Some(ct) =>
                      store.emojiExists(e.name).ifM(IO.pure(0), {
                        val key = s"emoji/${e.name}-${UUID.randomUUID()}"
                        bs.put(key, FPath.fromNioPath(file), ct) *> store.addEmoji(e.id, e.name, key, ct, users.get(e.creatorId)).map(if _ then 1 else 0)
                      })
                }.map(_.sum)
        _    <- log(s"カスタム絵文字: 新しく $n")
      yield MattermostImportReport(emoji = n)

  /** 画像の種類（先頭のバイトで見分ける）。 */
  private def imageType(p: Path): Option[String] =
    Option.when(Files.isRegularFile(p))(p).flatMap { f =>
      val in = Files.newInputStream(f)
      val h  = try in.readNBytes(12) finally in.close()
      def starts(bs: Int*) = h.length >= bs.size && bs.indices.forall(i => (h(i) & 0xff) == bs(i))
      if starts(0x89, 0x50, 0x4e, 0x47) then Some("image/png")
      else if starts(0xff, 0xd8, 0xff) then Some("image/jpeg")
      else if starts(0x47, 0x49, 0x46) then Some("image/gif")
      else if h.length >= 12 && new String(h.slice(8, 12), "ASCII") == "WEBP" then Some("image/webp")
      else None
    }

object MattermostImportReport:
  given cats.Monoid[MattermostImportReport] with
    def empty = MattermostImportReport()
    def combine(a: MattermostImportReport, b: MattermostImportReport) = MattermostImportReport(
      a.users + b.users, a.linkedUsers + b.linkedUsers, a.channels + b.channels, a.members + b.members, a.posts + b.posts,
      a.reactions + b.reactions, a.files + b.files, a.missingFiles + b.missingFiles, a.emoji + b.emoji, a.skipped + b.skipped)
