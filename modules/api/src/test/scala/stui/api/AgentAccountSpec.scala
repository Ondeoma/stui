package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import stui.core.*
import stui.db.*

import java.util.UUID

/**
 * エージェント専用アカウント（管理画面で作り、APIキーで MCP・REST API を使う）の流れ。
 * DB を使う。TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54379/stui_test）。
 */
class AgentAccountSpec extends CatsEffectSuite:

  private val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(url, sys.env.getOrElse("TEST_DATABASE_USER", "stui"), sys.env.getOrElse("TEST_DATABASE_PASSWORD", "stui")))

  private val xaFixture = ResourceSuiteLocalFixture("xa",
    cfg.fold(Resource.eval(IO.raiseError[doobie.Transactor[IO]](new RuntimeException("no db"))))(c =>
      Resource.eval(Database.migrate(c)) *> Database.transactor(c)))

  override def munitFixtures = if cfg.isDefined then List(xaFixture) else Nil

  /** 本番と同じ組み立て（Main.scala）のうち、この流れで使うルートだけ。 */
  private final class Env(xa: doobie.Transactor[IO]):
    val api      = ApiConfig(Set.empty, false, "http://localhost", devLogin = true)
    val users    = new UserStore(xa)
    val channels = new ChannelStore(xa)
    val messages = new MessageStore(xa)
    val activity = new ActivityStore(xa)
    val sess     = new SessionAuth(users, api)
    val access   = new Access2(channels, messages)
    val chat     = new ChatService(channels, messages, users, access, new NotifyService(activity, api, 60), new HookStore(xa), activity, api)
    val rest: HttpRoutes[IO] =
      new AuthRoutes(None, users, channels, activity, sess, access, api).routes <+>
        new UserRoutes(users, channels, activity, None, new EmojiStore(xa), sess, access).routes <+>
        new AgentRoutes(users, channels, chat, activity, sess, access).routes <+>
        new ChannelRoutes(channels, messages, users, activity, chat, sess, access).routes <+>
        new MessageRoutes(messages, channels, activity, chat, None, sess, access, api).routes
    val app: HttpApp[IO] = (rest <+> new McpRoutes(rest, sess, api.publicUrl).routes).orNotFound

    /** 呼ぶ。cookie はセッション、key は APIキー。 */
    def call(m: Method, path: String, body: Option[Json] = None, cookie: Option[String] = None, key: Option[String] = None): IO[(Status, Json)] =
      val r0 = Request[IO](m, Uri.unsafeFromString(path))
      val r1 = cookie.fold(r0)(c => r0.addCookie(api.cookieName, c))
      val r2 = key.fold(r1)(k => r1.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, k))))
      app.run(body.fold(r2)(r2.withEntity(_))).flatMap(r => r.bodyText.compile.string.map(s => r.status -> io.circe.parser.parse(s).getOrElse(Json.Null)))

    /** 開発用のログインで Cookie のセッションを得る。 */
    def login(name: String, admin: Boolean): IO[(String, UUID)] =
      app.run(Request[IO](Method.POST, uri"/auth/dev-login").withEntity(Json.obj("name" -> name.asJson, "admin" -> admin.asJson))).flatMap { r =>
        val c = r.cookies.find(_.name == api.cookieName).map(_.content).get
        call(Method.GET, "/api/me", cookie = Some(c)).map((_, j) => c -> j.hcursor.get[UUID]("id").toOption.get)
      }

  private def str(j: Json, path: String*): String = path.foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_)).as[String].getOrElse("")

  test("管理者がエージェント専用アカウントを作り、キーで MCP・REST API を使う。取り消し・無効化で使えなくなる。ログインはできない") {
    if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL が未設定"))
    else
      val e   = new Env(xaFixture())
      val tag = UUID.randomUUID().toString.take(8)
      import e.call
      import Method.*
      for
        la <- e.login(s"管理者-$tag", admin = true)
        (admin, adminId) = la
        ln <- e.login(s"一般-$tag", admin = false)
        normal = ln._1
        // 一般のユーザーは使えない
        r <- call(GET, "/api/admin/agents", cookie = Some(normal))
        _ = assertEquals(r._1, Status.Forbidden)
        r <- call(POST, "/api/admin/agents", Some(Json.obj("name" -> "x".asJson)), cookie = Some(normal))
        _ = assertEquals(r._1, Status.Forbidden)
        // 管理者の admin スコープの APIキーでも使えない（画面からのみ）
        r <- call(POST, "/api/me/api-keys", Some(Json.obj("name" -> "adm".asJson, "scopes" -> List("read", "write", "admin").asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Created)
        r <- call(GET, "/api/admin/agents", key = Some(str(r._2, "key")))
        _ = assertEquals(r._1, Status.Forbidden)
        // 作る（名前の確認）
        r <- call(POST, "/api/admin/agents", Some(Json.obj("name" -> "  ".asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.BadRequest)
        r <- call(POST, "/api/admin/agents", Some(Json.obj("name" -> ("a" * 61).asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.BadRequest)
        name = s"エージェント-$tag"
        r <- call(POST, "/api/admin/agents", Some(Json.obj("name" -> s"  $name ".asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Created)
        _ = assertEquals(str(r._2, "name"), name)
        agentId = UUID.fromString(str(r._2, "id"))
        agent <- e.users.find(agentId).map(_.get)
        _ = assert(AgentAccounts.isAgent(agent.sub) && agent.isBot && agent.email.isEmpty, agent.toString)
        // エージェントでないユーザーは対象外
        r <- call(PATCH, s"/api/admin/agents/$adminId", Some(Json.obj("name" -> "x".asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.NotFound)
        // キー: admin スコープは断る
        r <- call(POST, s"/api/admin/agents/$agentId/keys", Some(Json.obj("name" -> "k".asJson, "scopes" -> List("read", "admin").asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.BadRequest)
        r <- call(POST, s"/api/admin/agents/$agentId/keys", Some(Json.obj("name" -> "skakapo".asJson, "scopes" -> List("read", "write").asJson,
               "expiresInDays" -> 30.asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Created)
        key = str(r._2, "key")
        keyId = str(r._2, "info", "id")
        // MCP の tools/list と REST API（自分はエージェント専用アカウント）
        r <- call(POST, "/mcp", Some(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> "tools/list".asJson)), key = Some(key))
        _ = assertEquals(r._1, Status.Ok)
        _ = assert(r._2.hcursor.downField("result").downField("tools").values.exists(_.nonEmpty), r._2.noSpaces)
        r <- call(GET, "/api/me", key = Some(key))
        _ = assertEquals(str(r._2, "id"), agentId.toString)
        // 書き込み: 公開チャンネルに自分で参加して投稿する（投稿者はエージェント専用アカウント）
        r <- call(POST, "/api/channels", Some(Json.obj("name" -> s"agent-pub-$tag".asJson, "kind" -> "public".asJson, "memberIds" -> Json.arr())), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Created)
        pub = str(r._2, "id")
        r <- call(POST, s"/api/channels/$pub/join", key = Some(key))
        _ = assertEquals(r._1, Status.Ok)
        r <- call(POST, s"/api/channels/$pub/messages", Some(Json.obj("body" -> "エージェントからの投稿".asJson)), key = Some(key))
        _ = assertEquals(r._1, Status.Created, r._2.noSpaces)
        _ = assertEquals(str(r._2, "author", "id"), agentId.toString)
        // 非公開チャンネルは管理画面から参加させる
        r <- call(POST, "/api/channels", Some(Json.obj("name" -> s"agent-priv-$tag".asJson, "kind" -> "private".asJson, "memberIds" -> Json.arr())), cookie = Some(normal))
        priv = str(r._2, "id")
        r <- call(GET, s"/api/channels/$priv", key = Some(key))
        _ = assertEquals(r._1, Status.NotFound)
        r <- call(POST, s"/api/admin/agents/$agentId/channels", Some(Json.obj("channelIds" -> List(priv).asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Ok, r._2.noSpaces)
        r <- call(GET, s"/api/channels/$priv", key = Some(key))
        _ = assertEquals(r._1, Status.Ok)
        // 一覧（キーのハッシュは返さない）
        r <- call(GET, "/api/admin/agents", cookie = Some(admin))
        a = r._2.asArray.get.find(j => str(j, "id") == agentId.toString).get
        _ = assertEquals(a.hcursor.downField("keys").values.map(_.size), Some(1))
        _ = assertEquals(a.hcursor.downField("channels").values.map(_.size), Some(2))
        _ = assert(!r._2.noSpaces.toLowerCase.contains("hash"))
        // 無効にすると使えない・戻すと使える
        r <- call(PATCH, s"/api/admin/agents/$agentId", Some(Json.obj("disabled" -> true.asJson)), cookie = Some(admin))
        _ = assertEquals(r._1, Status.Ok)
        r <- call(GET, "/api/me", key = Some(key))
        _ = assertEquals(r._1, Status.Unauthorized)
        r <- call(PATCH, s"/api/admin/agents/$agentId", Some(Json.obj("disabled" -> false.asJson, "name" -> s"$name-2".asJson)), cookie = Some(admin))
        _ = assertEquals(str(r._2, "name"), s"$name-2")
        r <- call(GET, "/api/me", key = Some(key))
        _ = assertEquals(r._1, Status.Ok)
        // 取り消すと使えない
        r <- call(DELETE, s"/api/admin/agents/$agentId/keys/$keyId", cookie = Some(admin))
        _ = assertEquals(r._1, Status.NoContent)
        r <- call(GET, "/api/me", key = Some(key))
        _ = assertEquals(r._1, Status.Unauthorized)
        r <- call(GET, "/api/admin/agents", cookie = Some(admin))
        _ = assert(r._2.noSpaces.contains("\"revoked\":true"))
        // ログインはできない: 開発用のログインは dev: の sub だけを作る。OIDC はボットの sub を断る
        ls <- e.login(s"$name-2", admin = false)
        _ = assertNotEquals(ls._2, agentId)
        _ = assert(AgentAccounts.reservedSub(agent.sub))
        // 監査ログに残る
        log <- e.activity.auditLog(None, None, 200)
        _ = assert(List("agent.create", "agent.api_key.create", "agent.api_key.revoke", "agent.channel.join").forall(log.map(_.action).contains))
      yield ()
  }
