package stui.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import stui.core.*
import stui.db.*
import sbirds.mail.{Mail, Mailer}

import java.util.UUID
import scala.concurrent.duration.*

/** 通知（画面内・メール）。メールは少し待ってから送り、その間に画面で読んだら送らない。 */
final class NotifyService(activity: ActivityStore,
                          cfg: ApiConfig,
                          mailDelaySeconds: Int,
                          push: Option[sbirds.push.PushService] = None) {

  /** 1人に通知する（ボット・無効なユーザーは呼び出し側で除く）。 */
  def send(kind: String,
           to: UUID,
           actor: Option[UUID],
           channelId: Option[UUID],
           messageId: Option[UUID],
           title: String,
           body: String,
           link: String,
           mail: Boolean = true): IO[Unit] =
    sbirds.observe.Telemetry.span("notify.send", "kind" -> kind)(for {
      nid <- activity.notify(to, kind, channelId, messageId, actor, title, body.take(500))
      // この端末で受け取る設定（PWA のプッシュ通知）にしていれば、ブラウザを閉じていても知らせる
      _ <- push.traverse_(_.send(
        to,
        sbirds.push.PushMessage(title, body.take(500), link, Some(s"${channelId.getOrElse("")}:$kind"))
      ).start.void)
      _ <- IO.whenA(mail) {
        val text = List(
          title,
          "",
          body.take(2000),
          "",
          s"${cfg.publicUrl}$link",
          "",
          "-- ",
          "stui（通知の設定は画面右上のメニュー →「個人設定」で変えられます）"
        ).mkString("\n")
        activity.enqueueMail(
          Some(to),
          None,
          Some(nid),
          s"[stui] $title",
          text,
          Some(s"$to:${channelId.getOrElse("")}:$kind"),
          mailDelaySeconds
        )
      }
    } yield ()).handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.NotifyFailed, e.toString, e))

  /** メールの送信ワーカー（30秒ごと）。既読になった通知・止めた種類のメールは送らない。 */
  def mailLoop(mailer: Option[Mailer]): IO[Nothing] =
    Periodic.run(20.seconds, 30.seconds, ErrorCode.MailWorkerFailed) {
      activity.processMail(50) { row =>
        mailer match {
          case None => IO.pure(OutboxResult.Skip("SMTP が設定されていません"))
          case Some(m) =>
            (row.userId, row.toAddress) match {
              case (Some(uid), _) =>
                row.notificationId.fold(IO.pure(false))(activity.notificationRead).flatMap {
                  case true => IO.pure(OutboxResult.Skip("画面で既読になりました"))
                  case false =>
                    activity.mailTarget(uid, None).flatMap {
                      case None => IO.pure(OutboxResult.Skip("宛先のユーザーが無効か、メールアドレスがありません"))
                      case Some((addr, name)) => sbirds.observe.Telemetry.span("mail.send")(m.send(Mail(
                          addr,
                          name,
                          row.subject,
                          row.body
                        ))).as(OutboxResult.Sent).flatTap(_ => sbirds.observe.Metrics.count("stui.mails.sent"))
                    }
                }
              case (None, Some(addr)) => m.send(Mail(addr, addr, row.subject, row.body)).as(OutboxResult.Sent)
              case _ => IO.pure(OutboxResult.Skip("宛先がありません"))
            }
        }
      }
    }
}
