package stui.app

import sbirds.links.IntegrationTokenStore
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import sbirds.http.Errors
import stui.core.ErrorCode
import org.http4s.server.staticcontent.*
import stui.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import stui.auth.SecretBox
import stui.db.*
import sbirds.mail.{SmtpConfig, SmtpMailer}
import sbirds.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}
import sbirds.storage.{S3BlobStore, S3Config}

import scala.concurrent.duration.*

final case class AppConfig(
  port: Int,
  db: DbConfig,
  oidc: Option[OidcConfig],
  api: ApiConfig,
  staticDir: Option[String],
  s3: Option[S3Config],
  smtp: Option[SmtpConfig],
  appSecret: String,
  nodeId: String,
  mailDelaySeconds: Int)

object Main extends IOApp.Simple {

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private val version =
    Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for {
      // 名前解決の結果を30秒は使い回す。送信の Webhook で、確かめた IP と実際につなぐ IP を同じにする（DNS rebinding を防ぐ）
      _ <- IO(java.security.Security.setProperty("networkaddress.cache.ttl", "30"))
      cfg <- Config.load
      n <- migrateWithRetry(cfg)
      _ <- sbirds.observe.Log.println(s"[stui] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for {
        _ <- Telemetry.resource("stui", version, runtime)
        xa <- Database.transactor(cfg.db)
        _ <- gauges(xa)
        // 外への呼び出しもトレースに出す（traceparent を付けて送る）
        http <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build.map(HttpClientTelemetry(_))
        users = new UserStore(xa)
        channels = new ChannelStore(xa)
        messages = new MessageStore(xa)
        activity = new ActivityStore(xa)
        hooks = new HookStore(xa)
        search = new SearchStore(xa, messages)
        schedule = new ScheduleStore(xa, messages)
        emojis = new EmojiStore(xa)
        lock = new JobLock(xa, cfg.nodeId)
        box = SecretBox(cfg.appSecret)
        oidc <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        blobs <- blobStore(cfg)
        sess = new SessionAuth(users, cfg.api)
        access = new Access2(channels, messages)
        pushStore = new sbirds.push.PushStore(xa)
        push <- Resource.eval(sbirds.push.PushService.create(pushStore, http, cfg.api.publicUrl, "stui"))
        notifier = new NotifyService(activity, cfg.api, cfg.mailDelaySeconds, Some(push))
        chat = new ChatService(channels, messages, users, access, notifier, hooks, activity, cfg.api)
        webhooks = new WebhookService(hooks, users, channels, chat, http, cfg.api)
        sched = new ScheduleService(schedule, messages, channels, users, chat, notifier, sess)
        mailer = cfg.smtp.map(new SmtpMailer(_))
        feed <- Resource.eval(EventFeed(messages, activity))
        cache <- Resource.eval(LinkServiceRoutes.newCache)
        _ <- Resource.eval(startupNotes(cfg))
        loops = List[IO[Unit]](
          notifier.mailLoop(mailer),
          webhooks.loop,
          webhooks.purgeLoop,
          sched.reminderLoop,
          sched.scheduledLoop,
          feed.loop,
          purgeLoop(users, activity, messages, blobs, lock)
        )
        _ <- loops.traverse_(_.background)
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, channels, activity, sess, access, cfg.api).routes <+>
            new UserRoutes(users, channels, activity, blobs, emojis, sess, access).routes <+>
            new AgentRoutes(users, channels, chat, activity, sess, access).routes <+>
            new ChannelRoutes(channels, messages, users, activity, chat, sess, access).routes <+>
            new MessageRoutes(messages, channels, activity, chat, blobs, sess, access, cfg.api).routes <+>
            new SearchRoutes(search, channels, users, sess, access).routes <+>
            new NotificationRoutes(activity, sess, access).routes <+>
            new ScheduleRoutes(schedule, sched, activity, sess, access).routes <+>
            new HookRoutes(hooks, webhooks, channels, activity, sess, access, cfg.api).routes <+>
            new AppRoutes(channels, users, chat, notifier, sess, access, cfg.api).routes <+>
            new StreamRoutes(feed, channels, sess, access).routes <+>
            new OAuthRoutes(new OAuthStore(xa), activity, sess, access, cfg.api).routes <+>
            new LinkServiceRoutes(
              new IntegrationTokenStore(xa),
              http,
              box,
              cache,
              chat,
              sess,
              access,
              cfg.api
            ).routes <+>
            RumRoutes.routes <+>
            new PushRoutes(pushStore, push, sess, access).routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all = cfg.staticDir.fold(app)(d => app <+> spa(d))
        httpApp <- HttpTelemetry(
          Errors.handle(Guards(all, cfg.api).orNotFound, ErrorCode.Unexpected),
          List("/api", "/auth", "/mcp", "/oauth", "/hooks")
        )
        _ <- EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          .withShutdownTimeout(5.seconds)
          .withHttpApp(httpApp)
          .build
        _ <- Resource.eval(sbirds.observe.Log.println(s"[stui] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      } yield ()).useForever
    } yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(
    cfg: AppConfig,
    attempt: Int = 1
  ): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        sbirds.observe.Log.println(s"[stui] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(
    xa: doobie.Transactor[IO]
  ): Resource[IO, Unit] = {
    def q(
      sqlText: doobie.Fragment
    ): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge(
        "stui.users.total",
        "有効なユーザーの数（ボットを除く）"
      )(q(sql"select count(*) from app_user where not disabled and not is_bot")),
      Metrics.gauge(
        "stui.users.active_24h",
        "24時間以内にログインしたユーザーの数"
      )(q(sql"select count(*) from app_user where last_login_at > now() - interval '24 hours'")),
      Metrics.gauge(
        "stui.sessions.active",
        "有効なセッションの数"
      )(q(sql"select count(*) from session where expires_at > now()")),
      Metrics.gauge("stui.channels.total", "チャンネルの数")(q(sql"select count(*) from channel")),
      Metrics.gauge(
        "stui.messages.last_24h",
        "24時間以内の投稿の数"
      )(q(sql"select count(*) from message where created_at > now() - interval '24 hours'")),
      Metrics.gauge(
        "stui.mail.outbox",
        "送信待ちのメールの数"
      )(q(sql"select count(*) from mail_outbox where sent_at is null and not skipped")),
      Metrics.gauge(
        "stui.webhook.pending",
        "送信待ちの Webhook の数"
      )(q(sql"select count(*) from webhook_delivery where done_at is null and not failed"))
    ).sequence_
  }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(
    xa: doobie.Transactor[IO],
    node: String
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e) => text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(
    status: Status,
    body: String
  ): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def blobStore(
    cfg: AppConfig
  ): Resource[IO, Option[sbirds.storage.BlobStore]] = cfg.s3 match {
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(Some(_))
    case None => Resource.eval(sbirds.observe.Log.println("[stui] WARNING: S3_ENDPOINT が未設定のため、添付ファイルは使えません").as(None))
  }

  private def startupNotes(
    cfg: AppConfig
  ): IO[Unit] =
    IO.whenA(cfg.smtp.isEmpty)(sbirds.observe.Log.println("[stui] WARNING: SMTP_HOST が未設定のため、メールは送りません")) *>
      IO.whenA(
        cfg.oidc.isEmpty
      )(sbirds.observe.Log.println("[stui] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  /** 期限切れのセッション・古い通知・変更履歴・監査ログ・投稿されなかった添付を消す（1ノードだけ）。 */
  private def purgeLoop(
    users: UserStore,
    activity: ActivityStore,
    messages: MessageStore,
    blobs: Option[sbirds.storage.BlobStore],
    lock: JobLock
  ): IO[Unit] =
    Periodic.run(10.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> activity.purgeOld(180).void *> messages.purgeEvents(14).void *>
        activity.purgeAudit(730).void *>
        messages.orphanAttachments.flatMap(_.traverse_(
          (
            _,
            key
          ) => blobs.traverse_(_.delete(key).handleError(_ => ()))
        ))
    ))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(
    dir: String
  ): HttpRoutes[IO] = {
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _
          if !Set("api", "auth", "hooks", "mcp", "oauth").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    HttpRoutes[IO] { req =>
      val cc =
        if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc))
    }
  }
}
