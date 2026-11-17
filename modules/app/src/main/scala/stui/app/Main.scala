package stui.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.staticcontent.*
import stui.api.*
import stui.auth.{OidcClient, OidcConfig, SecretBox}
import stui.db.*
import stui.notify.{SmtpConfig, SmtpMailer}
import stui.shared.ErrorDto
import stui.storage.{S3BlobStore, S3Config}

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
    mailDelaySeconds: Int
)

/** 設定は環境変数から読む。 */
object Config:
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int]    = opt(name).flatMap(_.toIntOption)
  private def enabled(name: String): Boolean    = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** OAUTH_CLIENTS: [{"id":"skea","secret":"…","redirectUri":"https://…/callback","name":"skea","scopes":["read","write"]}] */
  private def oauthClients: Either[String, List[stui.api.OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ => "OAUTH_CLIENTS は JSON の配列で指定してください").flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) => id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || uri.startsWith("http://localhost")))
          .map((id, sec, uri) => stui.api.OAuthClient(id, sec, uri, c.get[String]("name").getOrElse(id),
            c.get[List[String]]("scopes").toOption.map(_.filter(Set("read", "write"))).filter(_.nonEmpty).getOrElse(List("read"))))
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for
      dbUrl  <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub    <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match
                case (Some(_), Some(_), Some(_)) if devLogin => Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
                case (Some(i), Some(c), Some(s)) =>
                  Right(Some(OidcConfig(i, c, s, s"${pub.stripSuffix("/")}/auth/callback", opt("OIDC_GROUPS_CLAIM").getOrElse("groups"))))
                case (None, None, None) if devLogin => Right(None)
                case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      secret <- opt("APP_SECRET") match
                  case Some(s) if s.length >= 16 => Right(s)
                  case Some(_) => Left("APP_SECRET は16文字以上にしてください")
                  case None if devLogin => Right("stui-development-secret")
                  case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      clients <- oauthClients
      tz <- opt("TIMEZONE").getOrElse("Asia/Tokyo") match
              case z if scala.util.Try(java.time.ZoneId.of(z)).isSuccess => Right(z)
              case z => Left(s"TIMEZONE が正しくありません: $z（例: Asia/Tokyo）")
    yield
      val base = pub.stripSuffix("/")
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
        oidc = oidc,
        api = ApiConfig(
          adminGroups = opt("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
          cookieSecure = base.startsWith("https://"),
          publicUrl = base,
          devLogin = devLogin,
          maxUploadBytes = int("MAX_UPLOAD_MB").getOrElse(100).toLong * 1024 * 1024,
          timezone = tz,
          webhookAllowHttp = enabled("WEBHOOK_ALLOW_HTTP"),
          webhookAllowPrivate = enabled("WEBHOOK_ALLOW_PRIVATE"),
          oauthClients = clients,
          // SKIWI_URL・SKIWI_CLIENT_ID・SKIWI_CLIENT_SECRET（SKAWAU_*・SKEA_*・SKAKA_* も同じ。*_INTERNAL_URL はサーバーから呼ぶ URL）
          linkServices = stui.api.LinkServiceRoutes.providers.flatMap { p =>
            val e = p.toUpperCase
            (opt(s"${e}_URL"), opt(s"${e}_CLIENT_ID"), opt(s"${e}_CLIENT_SECRET")).mapN((u, id, sec) =>
              stui.api.LinkService(p, u.stripSuffix("/"), id, sec, opt(s"${e}_INTERNAL_URL").map(_.stripSuffix("/"))))
          }
        ),
        staticDir = opt("STATIC_DIR"),
        s3 = opt("S3_ENDPOINT").map(ep => S3Config(ep, opt("S3_REGION").getOrElse("us-east-1"), opt("S3_BUCKET").getOrElse("stui"),
          opt("S3_ACCESS_KEY").getOrElse(""), opt("S3_SECRET_KEY").getOrElse(""), !opt("S3_PATH_STYLE").contains("false"))),
        smtp = opt("SMTP_HOST").map { h =>
          val tls = opt("SMTP_TLS").getOrElse("starttls").toLowerCase
          SmtpConfig(h, int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587), opt("SMTP_USER"),
            opt("SMTP_PASSWORD"), tls, opt("MAIL_FROM").getOrElse(s"stui@$h"))
        },
        appSecret = secret,
        nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" + java.util.UUID.randomUUID().toString.take(8)),
        mailDelaySeconds = int("MAIL_DELAY_MINUTES").getOrElse(10) * 60
      )
    ).left.map(new IllegalArgumentException(_))
  )

object Main extends IOApp.Simple:

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for
      // 名前解決の結果を30秒は使い回す。送信の Webhook で、確かめた IP と実際につなぐ IP を同じにする（DNS rebinding を防ぐ）
      _   <- IO(java.security.Security.setProperty("networkaddress.cache.ttl", "30"))
      cfg <- Config.load
      n   <- migrateWithRetry(cfg)
      _   <- IO.println(s"[stui] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for
        xa    <- Database.transactor(cfg.db)
        http  <- EmberClientBuilder.default[IO].withTimeout(30.seconds).build
        users    = new UserStore(xa)
        channels = new ChannelStore(xa)
        messages = new MessageStore(xa)
        activity = new ActivityStore(xa)
        hooks    = new HookStore(xa)
        search   = new SearchStore(xa, messages)
        schedule = new ScheduleStore(xa, messages)
        emojis   = new EmojiStore(xa)
        lock     = new JobLock(xa, cfg.nodeId)
        box      = new SecretBox(cfg.appSecret)
        oidc  <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        blobs <- blobStore(cfg)
        sess     = new SessionAuth(users, cfg.api)
        access   = new Access2(channels, messages)
        notifier = new NotifyService(activity, cfg.api, cfg.mailDelaySeconds)
        chat     = new ChatService(channels, messages, users, access, notifier, hooks, activity, cfg.api)
        webhooks = new WebhookService(hooks, users, channels, chat, http, cfg.api)
        sched    = new ScheduleService(schedule, messages, channels, users, chat, notifier, sess)
        mailer   = cfg.smtp.map(new SmtpMailer(_))
        feed    <- Resource.eval(EventFeed(messages, activity))
        cache   <- Resource.eval(LinkServiceRoutes.newCache)
        _ <- Resource.eval(startupNotes(cfg))
        loops = List[IO[Unit]](
                  notifier.mailLoop(mailer), webhooks.loop, webhooks.purgeLoop, sched.reminderLoop, sched.scheduledLoop, feed.loop,
                  purgeLoop(users, activity, messages, blobs, lock)
                )
        _ <- loops.traverse_(_.background)
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, channels, activity, sess, access, cfg.api).routes <+>
            new UserRoutes(users, channels, activity, blobs, emojis, sess, access).routes <+>
            new ChannelRoutes(channels, messages, users, activity, chat, sess, access).routes <+>
            new MessageRoutes(messages, channels, activity, chat, blobs, sess, access, cfg.api).routes <+>
            new SearchRoutes(search, channels, users, sess, access).routes <+>
            new NotificationRoutes(activity, sess, access).routes <+>
            new ScheduleRoutes(schedule, sched, activity, sess, access).routes <+>
            new HookRoutes(hooks, webhooks, channels, activity, sess, access, cfg.api).routes <+>
            new AppRoutes(channels, users, chat, notifier, sess, access, cfg.api).routes <+>
            new StreamRoutes(feed, channels, sess, access).routes <+>
            new OAuthRoutes(new OAuthStore(xa), activity, sess, access, cfg.api).routes <+>
            new LinkServiceRoutes(new IntegrationTokenStore(xa), http, box, cache, chat, sess, access, cfg.api).routes
        app = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        all = cfg.staticDir.fold(app)(d => app <+> spa(d))
        _ <- EmberServerBuilder.default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
               .withShutdownTimeout(5.seconds)
               .withHttpApp(ErrorHandling.Custom.recoverWith(Guards(all, cfg.api).orNotFound)(handler))
               .build
        _ <- Resource.eval(IO.println(s"[stui] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      yield ()).useForever
    yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else IO.println(s"[stui] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *> IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(xa: doobie.Transactor[IO], node: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true)  => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e)      => text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def blobStore(cfg: AppConfig): Resource[IO, Option[stui.storage.BlobStore]] = cfg.s3 match
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(Some(_))
    case None    => Resource.eval(IO.println("[stui] WARNING: S3_ENDPOINT が未設定のため、添付ファイルは使えません").as(None))

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    IO.whenA(cfg.smtp.isEmpty)(IO.println("[stui] WARNING: SMTP_HOST が未設定のため、メールは送りません")) *>
      IO.whenA(cfg.oidc.isEmpty)(IO.println("[stui] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください"))

  private val handler: PartialFunction[Throwable, IO[Response[IO]]] = {
    case _: org.http4s.server.middleware.EntityLimiter.EntityTooLarge => PayloadTooLarge(ErrorDto("本文が大きすぎます"))
    case e =>
    IO.println(s"[stui] unhandled error: $e") *> InternalServerError(ErrorDto("サーバーでエラーが発生しました"))
  }

  /** 期限切れのセッション・古い通知・変更履歴・監査ログ・投稿されなかった添付を消す（1ノードだけ）。 */
  private def purgeLoop(users: UserStore, activity: ActivityStore, messages: MessageStore, blobs: Option[stui.storage.BlobStore], lock: JobLock): IO[Unit] =
    Periodic.run(10.minutes, 1.hour)(e => s"[stui] purge failed: $e")(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> activity.purgeOld(180).void *> messages.purgeEvents(14).void *> activity.purgeAudit(730).void *>
        messages.orphanAttachments.flatMap(_.traverse_((_, key) => blobs.traverse_(_.delete(key).handleError(_ => ()))))))

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(dir: String): HttpRoutes[IO] =
    val files = fileService[IO](FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _ if !Set("api", "auth", "hooks", "mcp", "oauth").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    HttpRoutes[IO] { req =>
      val cc = if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc))
    }
