package stui.api

import sbirds.db.*
import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import sbirds.auth.Crypto
import stui.core.*
import stui.db.*
import stui.shared.*

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Webhook。受信は Slack・Mattermost と同じ形の JSON（text・username・icon_url・attachments）でチャンネルに投稿する。
 * 送信はイベントを JSON で POST し（HMAC-SHA256 の署名付き）、応答に text があればボットとして返信する。
 * http は送信先（利用者が設定した URL）へ送るクライアント（Main で sbirds.net.NetGuard.client から作る。リダイレクトは追わない）。
 */
final class WebhookService(hooks: HookStore,
                           users: UserStore,
                           channels: ChannelStore,
                           chat: ChatService,
                           http: Client[IO],
                           cfg: ApiConfig) {

  def validUrl(u: String): Either[AppError, String] = {
    val t = u.trim
    if t.startsWith("https://") || (cfg.webhookAllowHttp && t.startsWith("http://")) then
      Uri.fromString(t).left.map(_ => AppError(ErrorCode.WebhookUrlInvalid)).map(_ => t)
    else
      Left(AppError(
        ErrorCode.WebhookUrlInvalid,
        if cfg.webhookAllowHttp then "URL は http(s):// で始めてください" else "URL は https:// で始めてください"
      ))
  }

  private def str(c: ACursor, k: String): Option[String] = c.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  /** 受信の Webhook の投稿。 */
  def receive(h: IncomingRow, payload: Json): Res[MessageDto] = {
    val md = SlackPayload.toMarkdown(payload)
    if md.trim.isEmpty then IO.pure(Left(AppError(ErrorCode.WebhookTextRequired)))
    else
      channels.find(h.channelId).flatMap {
        case None => IO.pure(Left(AppError(ErrorCode.ChannelNotFound)))
        case Some(c) if c.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchived, "チャンネルはアーカイブされています")))
        case Some(c) =>
          val c0 = payload.hcursor
          val name = str(c0, "username").orElse(h.username).getOrElse(h.name).take(80)
          val icon = str(c0, "icon_url").orElse(h.iconUrl).filter(_.startsWith("https://"))
          // 送られてきたアイコン（画像・絵文字）が優先。なければ Webhook に選んだもの
          val sentIcon = str(c0, "icon_url").orElse(str(c0, "icon_emoji"))
          val emoji =
            str(c0, "icon_emoji").filter(_.length <= 40).map(HookIcons.normalize).orElse(if sentIcon.isEmpty &&
              h.iconUrl.isEmpty
            then h.iconEmoji
            else None)
          users.bot(s"bot:hook:${h.id}", h.name).flatMap(bot =>
            chat.publish(
              c,
              Author(bot, "webhook"),
              RichText.fromMarkdown(md),
              None,
              false,
              Json.obj(
                "username" -> name.asJson,
                "iconUrl" -> icon.asJson,
                "iconEmoji" -> emoji.asJson,
                "hook" -> h.id.asJson
              ).dropNullValues,
              "bot"
            )
          )
      }
  }

  /** 送信の応答に text があれば返信する（response_type が comment ならスレッドに）。 */
  private def reply(d: DeliveryRow, body: String): IO[Unit] =
    parse(body).toOption.filter(_ => d.event == EventKind.MessageCreated).traverse_ { j =>
      val c = j.hcursor
      str(c, "text").traverse_ { text =>
        val channelId = d.payload.hcursor.downField("channel").get[UUID]("id").toOption
        val msg = d.payload.hcursor.downField("message")
        val thread = msg.get[Option[UUID]]("rootId").toOption.flatten
          .orElse(Option.when(str(c, "response_type").contains("comment"))(msg.get[UUID]("id").toOption).flatten)
        channelId.flatTraverse(channels.find).flatMap(_.traverse_ { ch =>
          hooks.outgoingRow(d.webhookId).flatMap(_.traverse_ { h =>
            users.bot(s"bot:outhook:${h.id}", h.name).flatMap(bot =>
              chat.publish(
                ch,
                Author(bot, "webhook"),
                RichText.fromMarkdown(text),
                thread,
                false,
                Json.obj(
                  "username" -> str(c, "username").getOrElse(h.name).take(80).asJson,
                  "iconUrl" -> str(c, "icon_url").filter(_.startsWith("https://")).asJson,
                  "hook" -> h.id.asJson
                )
                  .dropNullValues,
                "bot"
              ).void
            )
          })
        })
      }
    }.handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.WebhookReplyFailed, e.toString, e))

  /**
   * 送信先が内部のネットワーク（ループバック・プライベート・リンクローカルなど）でないか。名前を引いた結果のすべての IP で確かめる（sbirds.net.NetGuard）。
   * 開発では WEBHOOK_ALLOW_PRIVATE=true で許す。
   */
  def publicHost(url: String): IO[Boolean] =
    if cfg.webhookAllowPrivate then IO.pure(true)
    else sbirds.net.NetGuard.isAllowed(url, sbirds.net.NetGuard.Policy.publicOnly)

  def send(d: DeliveryRow): IO[(OutboxResult, Option[Int])] =
    publicHost(d.url).flatMap {
      case false => IO.pure((OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした"), None))
      case true => sbirds.observe.Telemetry.span("webhook.deliver", "webhook.event" -> d.event)(deliver(d))
    }.flatTap {
      (r, _) =>
        val result = r match {
          case OutboxResult.Sent => "sent"
          case _: OutboxResult.Retry => "retry"
          case _ => "skip"
        }
        sbirds.observe.Metrics.count("stui.webhook.deliveries", "result" -> result)
    }

  /**
   * 送る要求。本文は payload.noSpaces そのもので、署名もこの本文で計算する。
   * CirceEntityCodec を import しているので、文字のエンコーダーを明示しないと本文が JSON の文字列（"{\"event\":...}"）になる。
   */
  def request(d: DeliveryRow): Request[IO] = {
    val body = d.payload.noSpaces
    val sig = "sha256=" + Crypto.hmacHex(d.secret, body.getBytes(StandardCharsets.UTF_8))
    Request[IO](Method.POST, Uri.unsafeFromString(d.url))
      .withEntity(body)(using EntityEncoder.stringEncoder)
      .withContentType(`Content-Type`(MediaType.application.json))
      .putHeaders(
        "X-Stui-Event" -> d.event,
        "X-Stui-Delivery" -> d.id.toString,
        "X-Stui-Signature" -> sig,
        "User-Agent" -> "stui-webhook"
      )
  }

  private def deliver(d: DeliveryRow): IO[(OutboxResult, Option[Int])] = {
    val req = request(d)
    http.run(req).use(r =>
      r.body.take(65536).through(fs2.text.utf8.decode).compile.string.map(r.status.code -> _)
    ).timeout(15.seconds).attempt.flatMap {
      case Right((c, text)) if c >= 200 && c < 300 => reply(d, text).as((OutboxResult.Sent, Some(c)))
      case Right((c, _)) if c == 410 => IO.pure((OutboxResult.Skip(s"HTTP $c"), Some(c)))
      case Right((c, _)) => IO.pure((OutboxResult.Retry(s"HTTP $c"), Some(c)))
      // 中身（内部の様子がわかる文言）は記録しない
      case Left(_: java.util.concurrent.TimeoutException) => IO.pure((OutboxResult.Retry("時間切れ"), None))
      // 確かめた後に名前の向き先が変わったとき（http は確かめた IP にそのままつなぐ）
      case Left(_: sbirds.net.NetGuard.Rejected) =>
        IO.pure((OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした"), None))
      case Left(_) => IO.pure((OutboxResult.Retry("接続できませんでした"), None))
    }
  }

  def loop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds, ErrorCode.WebhookWorkerFailed)(hooks.processDeliveries(50)(send))

  def purgeLoop: IO[Nothing] =
    Periodic.run(1.hour, 24.hours, ErrorCode.WebhookPurgeFailed)(hooks.purgeDeliveries(30))
}
