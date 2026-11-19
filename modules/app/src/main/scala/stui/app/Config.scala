package stui.app

import cats.effect.*
import cats.syntax.all.*
import stui.api.*
import sbirds.auth.OidcConfig
import stui.db.*
import sbirds.mail.SmtpConfig
import sbirds.storage.S3Config

/** 設定は環境変数から読む。 */
object Config {
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int] = opt(name).flatMap(_.toIntOption)
  private def enabled(name: String): Boolean = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** OAUTH_CLIENTS: [{"id":"skea","secret":"…","redirectUri":"https://…/callback","name":"skea","scopes":["read","write"]}] */
  private def oauthClients: Either[String, List[stui.api.OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ =>
        "OAUTH_CLIENTS は JSON の配列で指定してください"
      ).flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) =>
            id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || uri.startsWith("http://localhost"))
          )
          .map((id, sec, uri) =>
            stui.api.OAuthClient(
              id,
              sec,
              uri,
              c.get[String]("name").getOrElse(id),
              c.get[List[String]]("scopes").toOption.map(_.filter(Set(
                "read",
                "write"
              ))).filter(_.nonEmpty).getOrElse(List("read"))
            )
          )
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください")
      })
    }

  /** DB（DATABASE_URL・DATABASE_USER・DATABASE_PASSWORD）。取り込みのコマンドでも使う。 */
  def db: Either[String, DbConfig] =
    (required("DATABASE_URL"), required("DATABASE_USER"), required("DATABASE_PASSWORD"))
      .mapN((u, n, p) => DbConfig(u, n, p, int("DATABASE_POOL_SIZE").getOrElse(10)))

  /** 添付ファイルの置き場所（S3_*. 未設定なら None）。 */
  def s3: Option[S3Config] =
    opt("S3_ENDPOINT").map(ep =>
      S3Config(
        ep,
        opt("S3_REGION").getOrElse("us-east-1"),
        opt("S3_BUCKET").getOrElse("stui"),
        opt("S3_ACCESS_KEY").getOrElse(""),
        opt("S3_SECRET_KEY").getOrElse(""),
        !opt("S3_PATH_STYLE").contains("false")
      )
    )

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbCfg <- db
      pub <- required("PUBLIC_URL")
      devLogin = enabled("DEV_LOGIN")
      _ <- Either.cond(!(devLogin && pub.startsWith("https://")), (), "DEV_LOGIN（開発用のログイン）は https の PUBLIC_URL では使えません")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match {
        case (Some(_), Some(_), Some(_)) if devLogin =>
          Left("DEV_LOGIN（開発用のログイン）は OIDC と同時には使えません。本番では DEV_LOGIN を外してください")
        case (Some(i), Some(c), Some(s)) =>
          Right(Some(OidcConfig(
            i,
            c,
            s,
            s"${pub.stripSuffix("/")}/auth/callback",
            opt("OIDC_GROUPS_CLAIM").getOrElse("groups")
          )))
        case (None, None, None) if devLogin => Right(None)
        case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      }
      secret <- opt("APP_SECRET") match {
        case Some(s) if s.length >= 16 => Right(s)
        case Some(_) => Left("APP_SECRET は16文字以上にしてください")
        case None if devLogin => Right("stui-development-secret")
        case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      }
      clients <- oauthClients
      tz <- opt("TIMEZONE").getOrElse("Asia/Tokyo") match {
        case z if scala.util.Try(java.time.ZoneId.of(z)).isSuccess => Right(z)
        case z => Left(s"TIMEZONE が正しくありません: $z（例: Asia/Tokyo）")
      }
    } yield {
      val base = pub.stripSuffix("/")
      AppConfig(
        port = int("HTTP_PORT").getOrElse(8080),
        db = dbCfg,
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
              stui.api.LinkService(p, u.stripSuffix("/"), id, sec, opt(s"${e}_INTERNAL_URL").map(_.stripSuffix("/")))
            )
          }
        ),
        staticDir = opt("STATIC_DIR"),
        s3 = s3,
        smtp = opt("SMTP_HOST").map { h =>
          val tls = opt("SMTP_TLS").getOrElse("starttls").toLowerCase
          SmtpConfig(
            h,
            int("SMTP_PORT").getOrElse(if tls == "ssl" then 465 else if tls == "none" then 25 else 587),
            opt("SMTP_USER"),
            opt("SMTP_PASSWORD"),
            tls,
            opt("MAIL_FROM").getOrElse(s"stui@$h")
          )
        },
        appSecret = secret,
        nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" +
          java.util.UUID.randomUUID().toString.take(8)),
        mailDelaySeconds = int("MAIL_DELAY_MINUTES").getOrElse(10) * 60
      )
    }).left.map(new IllegalArgumentException(_))
  )
}
