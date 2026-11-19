package stui.api

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.Stream
import fs2.io.file.Path as FPath
import munit.CatsEffectSuite
import stui.core.*
import stui.db.*
import sbirds.storage.{BlobMeta, BlobStore}

import java.nio.file.Files
import java.util.UUID

/**
 * Mattermost からの取り込み。テスト用の DB の別のスキーマに Mattermost と同じ形の表（使う列だけ）を作り、そこから取り込む。
 * DB を使う。TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54379/stui_test）。
 */
class MattermostImportSpec extends CatsEffectSuite {

  private val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(url, sys.env.getOrElse("TEST_DATABASE_USER", "stui"), sys.env.getOrElse("TEST_DATABASE_PASSWORD", "stui"))
  )

  private val xaFixture = ResourceSuiteLocalFixture(
    "xa",
    cfg.fold(Resource.eval(IO.raiseError[doobie.Transactor[IO]](new RuntimeException("no db"))))(c =>
      Resource.eval(Database.migrate(c)) *> Database.transactor(c)
    )
  )

  override def munitFixtures = if cfg.isDefined then List(xaFixture) else Nil

  /** S3 の代わり（メモリに持つ）。 */
  private final class MemBlobs extends BlobStore {
    val data = scala.collection.concurrent.TrieMap.empty[String, Array[Byte]]
    def put(
      key: String,
      file: FPath,
      contentType: String
    ): IO[Unit] = IO.blocking(data.update(key, Files.readAllBytes(file.toNioPath)))
    def get(
      key: String
    ): IO[Option[(BlobMeta, Stream[IO, Byte])]] =
      IO(data.get(key).map(b => (BlobMeta("application/octet-stream", b.length.toLong), Stream.emits(b.toSeq))))
    def delete(
      key: String
    ): IO[Unit] = IO(data.remove(key)).void
    def copy(
      from: String,
      to: String
    ): IO[Boolean] = IO(data.get(from).fold(false)(b => { data.update(to, b); true }))
  }

  private val ddl = List(
    "create table teams (id text, name text, displayname text, deleteat bigint default 0)",
    """create table users (id text, username text, email text, firstname text, lastname text, nickname text, createat bigint,
       deleteat bigint default 0)""",
    "create table bots (userid text)",
    """create table channels (id text, teamid text, type text, name text, displayname text, header text, purpose text, creatorid text,
       createat bigint, deleteat bigint default 0)""",
    "create table channelmembers (channelid text, userid text, schemeadmin boolean, lastviewedat bigint)",
    """create table posts (id text, channelid text, userid text, rootid text default '', message text, type text default '', props jsonb default '{}',
       createat bigint, editat bigint default 0, deleteat bigint default 0, ispinned boolean default false)""",
    "create table reactions (postid text, userid text, emojiname text, createat bigint, deleteat bigint default 0)",
    """create table fileinfo (id text, postid text, path text, name text, size bigint, mimetype text, creatorid text, createat bigint,
       deleteat bigint default 0)""",
    "create table emoji (id text, name text, creatorid text, createat bigint, deleteat bigint default 0)"
  )

  private def run(
    xa: Transactor[IO],
    sqls: String*
  ): IO[Unit] = sqls.toList.traverse_(s => Fragment.const(s).update.run.transact(xa))

  test("ユーザー・チャンネル・DM・投稿・スレッド・メンション・リアクション・添付・絵文字を取り込み、やり直しても二重に作らない") {
    if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL が未設定"))
    else {
      val xa = xaFixture()
      val c = cfg.get
      val schema = s"mm_${UUID.randomUUID().toString.take(8)}"
      val r = UUID.randomUUID().toString.take(6)
      def id(
        s: String
      ) = s"$s$r"
      val srcXa = Transactor.fromDriverManager[IO](
        "org.postgresql.Driver",
        c.url + (if c.url.contains("?") then "&" else "?") + s"currentSchema=$schema",
        c.user,
        c.password,
        None
      )
      val dir = Files.createTempDirectory("mm-files")
      val blobs = new MemBlobs
      val users = new UserStore(xa)
      val logs = List.newBuilder[String]
      def importer = new MattermostImporter(
        new MattermostSource(srcXa),
        new ImportStore(xa, "mattermost"),
        Some(blobs),
        s => IO(logs += s)
      )
      val t0 = 1_700_000_000_000L
      val clash = s"雑談-$r"
      for {
        _ <- run(xa, s"create schema $schema")
        _ <- ddl.traverse_(d => run(srcXa, d))
        // 既存の stui のユーザー（同じメールアドレス → 結び付ける）と、名前がぶつかるチャンネル
        alice <- users.upsert(s"test:${UUID.randomUUID()}", "アリス（stui）", Some(s"alice-$r@example.com"), None)
        _ <- new ChannelStore(xa).create(clash, "public", "", "", alice.id, List(alice.id))
        _ <- IO(Files.createDirectories(dir.resolve("2026/f1")))
        _ <- IO(Files.writeString(dir.resolve("2026/f1/memo.txt"), "メモの中身"))
        _ <- IO(Files.createDirectories(dir.resolve(s"emoji/${id("e1")}")))
        _ <- IO(Files.write(
          dir.resolve(s"emoji/${id("e1")}/image"),
          Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ))
        _ <- run(
          srcXa,
          s"insert into teams values ('${id("t1")}', 'dev', '開発チーム', 0), ('${id("t2")}', 'ops', '運用', 0)",
          s"""insert into users values
                       ('${id("ua")}', 'alice', 'ALICE-$r@example.com', 'Alice', '', '', $t0, 0),
                       ('${id("ub")}', 'bob', 'bob-$r@example.com', 'Bob', 'Smith', '', $t0, 0),
                       ('${id("uc")}', 'carol', 'carol-$r@example.com', '', '', 'キャロル', $t0, ${t0 + 1}),
                       ('${id("uh")}', 'cibot', 'cibot@localhost', '', '', '', $t0, 0)""",
          s"insert into bots values ('${id("uh")}')",
          s"""insert into channels values
                       ('${id("c1")}', '${id("t1")}', 'O', 'town-square', 'Town Square $r', 'ヘッダー', '目的', '${id(
              "ub"
            )}', $t0, 0),
                       ('${id("c2")}', '${id("t1")}', 'O', 'chat', '$clash', '', '', '${id("ub")}', $t0, 0),
                       ('${id("c3")}', '${id("t2")}', 'O', 'ops', '運用 $r', '', '', '${id("ub")}', $t0, 0),
                       ('${id("cd")}', '', 'D', '${id("ua")}__${id("ub")}', '', '', '', '', $t0, 0)""",
          s"""insert into channelmembers values
                       ('${id("c1")}', '${id("ua")}', false, ${t0 + 2500}), ('${id("c1")}', '${id("ub")}', true, ${t0 +
              1500}),
                       ('${id("c2")}', '${id("ub")}', false, 0),
                       ('${id("cd")}', '${id("ua")}', false, 0), ('${id("cd")}', '${id("ub")}', false, 0)""",
          s"""insert into posts (id, channelid, userid, rootid, message, type, props, createat, editat, deleteat, ispinned) values
                       ('${id("p1")}', '${id("c1")}', '${id("ub")}', '', 'こんにちは @alice さん', '', '{}', ${t0 +
              1000}, 0, 0, true),
                       ('${id("p2")}', '${id("c1")}', '${id("ua")}', '${id("p1")}', '返信です `@bob`', '', '{}', ${t0 +
              2000}, ${t0 + 2100}, 0, false),
                       ('${id("p3")}', '${id("c1")}', '${id(
              "ua"
            )}', '', 'alice joined', 'system_join_channel', '{}', ${t0 + 3000}, 0, 0, false),
                       ('${id("p4")}', '${id("c1")}', '${id(
              "ub"
            )}', '', 'ビルド', '', '{"from_webhook":"true","override_username":"CI"}', ${t0 + 4000}, 0, 0, false),
                       ('${id("p5")}', '${id("c1")}', '${id("ub")}', '', '消した投稿', '', '{}', ${t0 + 5000}, 0, ${t0 +
              5001}, false),
                       ('${id("p6")}', '${id("cd")}', '${id("ua")}', '', 'DM です', '', '{}', ${t0 + 6000}, 0, 0, false),
                       ('${id("p7")}', '${id("c3")}', '${id("ub")}', '', '別のチーム', '', '{}', ${t0 +
              7000}, 0, 0, false)""",
          s"""insert into reactions values ('${id("p1")}', '${id("ua")}', '+1', ${t0 + 1100}, 0),
                       ('${id("p1")}', '${id("ua")}', 'partyparrot', ${t0 + 1200}, 0), ('${id("p1")}', '${id(
              "ub"
            )}', 'smile', ${t0 + 1300}, ${t0 + 1301})""",
          s"""insert into fileinfo values ('${id("f1")}', '${id(
              "p1"
            )}', '2026/f1/memo.txt', 'memo.txt', 15, 'text/plain', '${id("ub")}', ${t0 + 1000}, 0),
                       ('${id("f2")}', '${id("p1")}', '2026/f2/missing.png', 'missing.png', 1, 'image/png', '${id(
              "ub"
            )}', ${t0 + 1000}, 0)""",
          s"insert into emoji values ('${id("e1")}', 'partyparrot$r', '${id("ub")}', $t0, 0)"
        )
        // 書き込まずに数えるだけ
        dry <- importer.run(MattermostImportOptions(teams = List("dev"), dryRun = true))
        _ = assertEquals((dry.users, dry.channels, dry.posts), (4, 3, 5))
        rep <- importer.run(MattermostImportOptions(teams = List("dev"), filesDir = Some(dir)))
        _ = assertEquals(
          (rep.users, rep.linkedUsers, rep.channels, rep.posts, rep.reactions, rep.files, rep.missingFiles, rep.emoji),
          (3, 1, 3, 4, 2, 1, 1, 1),
          logs.result().mkString("\n")
        )
        maps <-
          sql"select kind, source_id, target_id from import_map where source = 'mattermost' and source_id like ${"%" + r}"
            .query[(String, String, UUID)].to[List].transact(xa).map(_.map(
              (
                k,
                s,
                t
              ) => (k, s.dropRight(r.length)) -> t
            ).toMap)
        _ = assertEquals(maps(("user", "ua")), alice.id)
        // チャンネル（名前がぶつかったものはチーム名を付ける. 別のチームのものは取り込まない）
        names <-
          sql"select c.name, c.kind, c.topic from channel c where c.id = any(${List(maps(("channel", "c1")), maps(("channel", "c2"))).toArray})"
            .query[(String, String, String)].to[List].transact(xa)
        _ = assertEquals(names.map(_._1).toSet, Set(s"Town Square $r", s"$clash (開発チーム)"))
        _ = assert(names.exists(_._3 == "ヘッダー"))
        _ = assert(!maps.contains(("channel", "c3")))
        dm <- sql"select kind, dm_key from channel where id = ${maps(("channel", "cd"))}".query[(
          String,
          String
        )].unique.transact(xa)
        _ = assertEquals(dm, ("dm", DmKey.of(List(alice.id, maps(("user", "ub"))))))
        // スレッド・ピン留め・編集・Webhook・メンション
        p1 <-
          sql"select reply_count, pinned_at is not null, body::text, body_text from message where id = ${maps(("post", "p1"))}"
            .query[(Int, Boolean, String, String)].unique.transact(xa)
        _ = assertEquals((p1._1, p1._2), (1, true))
        _ = assert(p1._3.contains(s""""id": "${alice.id}"""") || p1._3.contains(s""""id":"${alice.id}""""), p1._3)
        _ = assert(p1._4.contains("memo.txt"), p1._4)
        p2 <- sql"select root_id, edited_at is not null from message where id = ${maps(("post", "p2"))}".query[(
          UUID,
          Boolean
        )].unique.transact(xa)
        _ = assertEquals(p2, (maps(("post", "p1")), true))
        p4 <- sql"select kind, props->>'username' from message where id = ${maps(("post", "p4"))}".query[(
          String,
          String
        )].unique.transact(xa)
        _ = assertEquals(p4, ("bot", "CI"))
        _ = assert(!maps.contains(("post", "p3")) && !maps.contains(("post", "p5")) && !maps.contains(("post", "p7")))
        follow <-
          sql"select count(*) from thread_follow where root_id = ${maps(("post", "p1"))}".query[Int].unique.transact(xa)
        _ = assertEquals(follow, 2)
        // 既読の位置（アリスは返信まで見た・ボブは最初の投稿まで）
        seqs <-
          sql"select seq from message where id = any(${List(maps(("post", "p1")), maps(("post", "p2"))).toArray}) order by seq"
            .query[Long].to[List].transact(xa)
        reads <-
          sql"select user_id, last_read_seq from channel_member where channel_id = ${maps(("channel", "c1"))}".query[(
            UUID,
            Long
          )].to[List]
            .transact(xa).map(_.toMap)
        _ = assertEquals((reads(alice.id), reads(maps(("user", "ub")))), (seqs(1), seqs(0)))
        // リアクション・添付・カスタム絵文字・消したユーザー
        reacts <- sql"select emoji from reaction where message_id = ${maps(("post", "p1"))} order by emoji".query[
          String
        ].to[List].transact(xa)
        _ = assertEquals(reacts.toSet, Set("👍", ":partyparrot:"))
        att <- sql"select s3_key, file_name from attachment where message_id = ${maps(("post", "p1"))}".query[(
          String,
          String
        )].unique.transact(xa)
        _ = assertEquals(new String(blobs.data(att._1), "UTF-8"), "メモの中身")
        _ = assertEquals(att._2, "memo.txt")
        emoji <- sql"select count(*) from custom_emoji where name = ${s"partyparrot$r"}".query[Int].unique.transact(xa)
        _ = assertEquals(emoji, 1)
        carol <- users.find(maps(("user", "uc")))
        _ = assertEquals(carol.map(u => (u.name, u.disabled)), Some(("キャロル", true)))
        // やり直しても二重に作らない。あとから増えた投稿だけを足す
        _ <-
          run(srcXa, s"insert into posts (id, channelid, userid, message, createat) values ('${id("p8")}', '${id("c1")}', '${id("ub")}', '追加', ${t0 + 8000})")
        again <- importer.run(MattermostImportOptions(teams = List("dev"), filesDir = Some(dir)))
        _ = assertEquals(
          (again.users, again.channels, again.members, again.posts, again.reactions, again.files, again.emoji),
          (0, 0, 0, 1, 0, 0, 0)
        )
        // 取り込んだユーザーは、同じメールアドレスで最初にログインしたときに引き継がれる
        bob <- users.upsert(s"oidc:${UUID.randomUUID()}", "Bob Smith", Some(s"BOB-$r@example.com"), None)
        _ = assertEquals(bob.id, maps(("user", "ub")))
        _ <- run(xa, s"drop schema $schema cascade")
      } yield ()
    }
  }
}
