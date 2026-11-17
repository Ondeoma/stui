package stui.notify

import cats.effect.*
import jakarta.mail.*
import jakarta.mail.internet.{InternetAddress, MimeMessage}

import java.util.{Date, Properties}

/** SMTPの接続設定。tls: "starttls"（既定・587番）| "ssl"（465番）| "none"（社内リレー等）。 */
final case class SmtpConfig(
    host: String,
    port: Int = 587,
    user: Option[String] = None,
    password: Option[String] = None,
    tls: String = "starttls",
    from: String,
    timeoutMillis: Int = 15000
)

final case class Mail(to: String, toName: String, subject: String, body: String)

trait Mailer:
  def send(m: Mail): IO[Unit]

/** Jakarta Mail による送信。本文はUTF-8のプレーンテキスト。 */
final class SmtpMailer(cfg: SmtpConfig) extends Mailer:

  private val session: Session =
    val p = new Properties()
    p.put("mail.smtp.host", cfg.host)
    p.put("mail.smtp.port", cfg.port.toString)
    p.put("mail.smtp.connectiontimeout", cfg.timeoutMillis.toString)
    p.put("mail.smtp.timeout", cfg.timeoutMillis.toString)
    p.put("mail.smtp.writetimeout", cfg.timeoutMillis.toString)
    cfg.tls match
      case "ssl" =>
        p.put("mail.smtp.ssl.enable", "true")
        p.put("mail.smtp.ssl.checkserveridentity", "true")
      case "starttls" =>
        p.put("mail.smtp.starttls.enable", "true")
        p.put("mail.smtp.starttls.required", "true")
        p.put("mail.smtp.ssl.checkserveridentity", "true")
      case _ => ()
    val auth = cfg.user.map { u =>
      p.put("mail.smtp.auth", "true")
      new Authenticator:
        override def getPasswordAuthentication = new PasswordAuthentication(u, cfg.password.getOrElse(""))
    }
    Session.getInstance(p, auth.orNull)

  def send(m: Mail): IO[Unit] = IO.blocking {
    val msg = new MimeMessage(session)
    msg.setFrom(new InternetAddress(cfg.from))
    msg.setRecipient(Message.RecipientType.TO, new InternetAddress(m.to, m.toName, "UTF-8"))
    msg.setSubject(m.subject, "UTF-8")
    msg.setText(m.body, "UTF-8")
    msg.setSentDate(new Date())
    Transport.send(msg)
  }
