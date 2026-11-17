package stui.db

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import stui.core.*
import stui.shared.*

import java.time.{Instant, ZoneId}
import java.util.UUID

/** DB を使うテスト。TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54379/stui_test）。 */
class StoreSpec extends CatsEffectSuite:

  private val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(url, sys.env.getOrElse("TEST_DATABASE_USER", "stui"), sys.env.getOrElse("TEST_DATABASE_PASSWORD", "stui")))

  private val xaFixture = ResourceSuiteLocalFixture("xa",
    cfg.fold(Resource.eval(IO.raiseError[doobie.Transactor[IO]](new RuntimeException("no db"))))(c =>
      Resource.eval(Database.migrate(c)) *> Database.transactor(c)))

  override def munitFixtures = if cfg.isDefined then List(xaFixture) else Nil

  final case class Stores(users: UserStore, channels: ChannelStore, messages: MessageStore, search: SearchStore, schedule: ScheduleStore,
      activity: ActivityStore, hooks: HookStore)

  private def withDb(name: String)(body: Stores => IO[Unit]): Unit =
    test(name) {
      if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL が未設定"))
      else
        val xa = xaFixture()
        val messages = new MessageStore(xa)
        body(Stores(new UserStore(xa), new ChannelStore(xa), messages, new SearchStore(xa, messages), new ScheduleStore(xa, messages),
          new ActivityStore(xa), new HookStore(xa)))
    }

  private def user(st: Stores, name: String): IO[User] = st.users.upsert(s"test:${UUID.randomUUID()}", name, None, None)

  private def uniq(prefix: String) = prefix + "-" + UUID.randomUUID().toString.take(8)

  private def post(st: Stores, c: UUID, u: UUID, text: String, root: Option[UUID] = None): IO[MessageRow] =
    val doc = RichText.fromPlain(text)
    st.messages.insert(NewMessage(c, root, false, u, "user", "web", doc, RichText.toPlain(doc), Json.obj()))

  withDb("チャンネルの作成・名前の重複・参加と未読の数") { st =>
    for
      a <- user(st, "山田 太郎")
      b <- user(st, "佐藤 花子")
      name = uniq("開発")
      c <- st.channels.create(name, ChannelKind.Public, "トピック", "", a.id, List(b.id)).map(_.toOption.get)
      dup <- st.channels.create(name.toUpperCase, ChannelKind.Public, "", "", a.id, Nil)
      _ = assert(dup.isLeft)
      _ <- post(st, c.id, a.id, "こんにちは")
      m2 <- post(st, c.id, a.id, "二つ目")
      lb <- st.channels.listForUser(b.id).map(_.find(_.id == c.id).get)
      la <- st.channels.listForUser(a.id).map(_.find(_.id == c.id).get)
      _ = assertEquals(lb.unread, 2)
      _ = assertEquals(la.unread, 0) // 自分の投稿は未読にしない
      _ = assertEquals(la.role, Some(ChannelRole.Admin))
      _ <- st.channels.markRead(c.id, b.id, m2.seq)
      lb2 <- st.channels.get(c.id, b.id).map(_.get)
      _ = assertEquals(lb2.unread, 0)
      page <- st.messages.page(c.id, b.id, None, None, None, 50)
      _ = assertEquals(page.messages.map(m => RichText.toPlain(m.body)), List("こんにちは", "二つ目"))
    yield ()
  }

  withDb("スレッド: 返信数・フォロー・チャンネルの一覧には出さない・削除") { st =>
    for
      a <- user(st, "親 の人")
      b <- user(st, "返信 する人")
      c <- st.channels.create(uniq("スレッド"), ChannelKind.Public, "", "", a.id, List(b.id)).map(_.toOption.get)
      root <- post(st, c.id, a.id, "親")
      r1 <- post(st, c.id, b.id, "返信1", Some(root.id))
      _ <- post(st, c.id, b.id, "返信2", Some(root.id))
      t <- st.messages.thread(root.id, a.id).map(_.get)
      _ = assertEquals(t._1.replyCount, 2)
      _ = assertEquals(t._2.size, 2)
      _ = assertEquals(t._1.replyUsers.map(_.id), List(b.id))
      fol <- st.messages.followers(root.id)
      _ = assertEquals(fol.toSet, Set(a.id, b.id))
      page <- st.messages.page(c.id, a.id, None, None, None, 50)
      _ = assertEquals(page.messages.map(_.id), List(root.id))
      ft <- st.messages.followedThreads(a.id, 10)
      _ = assertEquals(ft.find(_._1.id == root.id).map(_._3), Some(2))
      _ <- st.messages.delete(r1.id, b.id)
      t2 <- st.messages.thread(root.id, a.id).map(_.get)
      _ = assertEquals(t2._1.replyCount, 1)
      // 返信のある親を消しても、一覧には「削除されました」として残る
      _ <- st.messages.delete(root.id, a.id)
      page2 <- st.messages.page(c.id, a.id, None, None, None, 50)
      _ = assertEquals(page2.messages.map(m => (m.id, m.deleted)), List(root.id -> true))
    yield ()
  }

  withDb("リアクション・ピン留め・保存") { st =>
    for
      a <- user(st, "リアクション")
      c <- st.channels.create(uniq("リアクション"), ChannelKind.Private, "", "", a.id, Nil).map(_.toOption.get)
      m <- post(st, c.id, a.id, "いいね")
      _ <- st.messages.react(m.id, a.id, "👍", on = true)
      _ <- st.messages.react(m.id, a.id, "👍", on = true)
      _ <- st.messages.pin(m.id, a.id, on = true)
      _ <- st.messages.save(m.id, a.id, on = true)
      v <- st.messages.view(m.id, a.id).map(_.get)
      _ = assertEquals(v.reactions.map(r => r.emoji -> r.users.size), List("👍" -> 1))
      _ = assert(v.pinned && v.saved)
      pins <- st.messages.pinned(c.id, a.id)
      _ = assertEquals(pins.map(_.id), List(m.id))
      saved <- st.messages.saved(a.id, 10)
      _ = assert(saved.exists(_._1.id == m.id))
    yield ()
  }

  withDb("DM: 同じ顔ぶれは1つ・グループ DM・自分だけの DM") { st =>
    for
      a <- user(st, "DM A")
      b <- user(st, "DM B")
      x <- user(st, "DM C")
      d1 <- st.channels.openDm(List(a.id, b.id))
      d2 <- st.channels.openDm(List(b.id, a.id))
      _ = assertEquals(d1.id, d2.id)
      _ = assertEquals(d1.kind, ChannelKind.Dm)
      g <- st.channels.openDm(List(a.id, b.id, x.id))
      _ = assertEquals(g.kind, ChannelKind.Group)
      self <- st.channels.openDm(List(a.id))
      la <- st.channels.listForUser(a.id)
      _ = assertEquals(la.find(_.id == d1.id).map(_.dmUsers.map(_.id)), Some(List(b.id)))
      _ = assertEquals(la.find(_.id == self.id).map(_.dmUsers.map(_.id)), Some(List(a.id)))
    yield ()
  }

  withDb("検索: 日本語の部分一致・かなの揺れ・絞り込み・見られないチャンネルは除く") { st =>
    val word = "検索" + UUID.randomUUID().toString.filter(_.isDigit).take(6)
    for
      a <- user(st, "検索 する人")
      b <- user(st, "部外 者")
      pub <- st.channels.create(uniq("公開"), ChannelKind.Public, "", "", a.id, Nil).map(_.toOption.get)
      priv <- st.channels.create(uniq("非公開"), ChannelKind.Private, "", "", a.id, Nil).map(_.toOption.get)
      _ <- post(st, pub.id, a.id, s"サーバーの${word}について https://example.com")
      _ <- post(st, priv.id, a.id, s"ひみつの$word")
      q = SearchQuery.parse(word).toOption.get
      ra <- st.search.search(a.id, ZoneId.of("Asia/Tokyo"), q, 20, 0).map(_.toOption.get)
      rb <- st.search.search(b.id, ZoneId.of("Asia/Tokyo"), q, 20, 0).map(_.toOption.get)
      _ = assertEquals(ra._2, 2)
      _ = assertEquals(rb._2, 1)
      kana <- st.search.search(a.id, ZoneId.of("Asia/Tokyo"), SearchQuery.parse(s"さーばー $word").toOption.get, 20, 0).map(_.toOption.get)
      _ = assertEquals(kana._2, 1)
      link <- st.search.search(a.id, ZoneId.of("Asia/Tokyo"), SearchQuery.parse(s"$word has:link").toOption.get, 20, 0).map(_.toOption.get)
      _ = assertEquals(link._1.map(_.channel.id), List(pub.id))
      inCh <- st.search.search(a.id, ZoneId.of("Asia/Tokyo"), SearchQuery.parse(s"$word in:#${priv.name}").toOption.get, 20, 0).map(_.toOption.get)
      _ = assertEquals(inCh._1.map(_.channel.id), List(priv.id))
      from <- st.search.search(b.id, ZoneId.of("Asia/Tokyo"), SearchQuery.parse(s"from:me $word").toOption.get, 20, 0).map(_.toOption.get)
      _ = assertEquals(from._2, 0)
    yield ()
  }

  withDb("リマインダー: 時刻になったものを1回だけ処理する") { st =>
    for
      a <- user(st, "リマインド")
      _ <- st.schedule.createReminder(a.id, "会議の準備", None, Instant.now().minusSeconds(5))
      _ <- st.schedule.createReminder(a.id, "まだ先", None, Instant.now().plusSeconds(3600))
      fired <- Ref.of[IO, List[String]](Nil)
      _ <- st.schedule.processDueReminders(100)(r => IO.whenA(r.userId == a.id)(fired.update(r.text :: _)))
      _ <- st.schedule.processDueReminders(100)(r => IO.whenA(r.userId == a.id)(fired.update(r.text :: _)))
      fs <- fired.get
      _ = assertEquals(fs, List("会議の準備"))
      rs <- st.schedule.reminders(a.id, includeDone = false)
      _ = assertEquals(rs.map(r => r.text -> r.fired), List("会議の準備" -> true, "まだ先" -> false))
    yield ()
  }

  withDb("変更履歴: 参加しているチャンネルの変更だけを返す") { st =>
    for
      a <- user(st, "変更 A")
      b <- user(st, "変更 B")
      since <- st.messages.latestEvent
      c <- st.channels.create(uniq("変更"), ChannelKind.Private, "", "", a.id, Nil).map(_.toOption.get)
      m <- post(st, c.id, a.id, "非公開の投稿")
      ca <- st.messages.changes(a.id, since, None)
      cb <- st.messages.changes(b.id, since, None)
      _ = assert(ca.events.exists(_.messageId.contains(m.id)))
      _ = assert(!cb.events.exists(_.channelId == c.id))
    yield ()
  }

  withDb("本文に埋め込んだ画像はメッセージに結び付け、添付の一覧には出さない") { st =>
    for
      a <- user(st, "画像 の人")
      c <- st.channels.create(uniq("画像"), ChannelKind.Public, "", "", a.id, Nil).map(_.toOption.get)
      img  <- st.messages.addAttachment(c.id, "k1", "図.png", 10, "image/png", "x", a.id)
      file <- st.messages.addAttachment(c.id, "k2", "資料.pdf", 10, "application/pdf", "y", a.id)
      doc = io.circe.parser.parse(s"""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"見て"}]},
              {"type":"image","attrs":{"src":"${Files.url(img.id, "図.png")}","alt":"図.png"}}]}""").toOption.get
      m <- st.messages.insert(NewMessage(c.id, None, false, a.id, "user", "web", doc, RichText.toPlain(doc), Json.obj(), List(file.id)))
      v <- st.messages.view(m.id, a.id).map(_.get)
      _ = assertEquals(v.attachments.map(_.fileName), List("資料.pdf"))
      linked <- st.messages.attachment(img.id)
      _ = assertEquals(linked.flatMap(_.messageId), Some(m.id))
      _ = assert(v.html.contains("<img"))
    yield ()
  }
