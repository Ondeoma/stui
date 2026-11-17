package stui.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

/**
 * 兄弟のサービス（skea・skiwi・skawau・skuaka・skaka）からの通知（docs/integrations.md）。
 * OAuth で発行したトークン（write）で呼ぶ。ボット（サービスの名前）から本人への DM、または本人が参加しているチャンネルへ投稿する。
 */
final class AppRoutes(channels: ChannelStore, users: UserStore, chat: ChatService, notifier: NotifyService, val sessions: SessionAuth,
    val access: Access2, cfg: ApiConfig) extends RouteSupport:

  private def str(j: Json, k: String): Option[String] = j.hcursor.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    /**
     * 通知を送る。{text: Markdown, title?, url?, channel?: チャンネルの ID か名前}。
     * channel がなければボットから本人への DM（画面内の通知も出す）。
     */
    case req @ POST -> Root / "api" / "apps" / "notify" =>
      authed(req)(s => s.oauthClient.flatMap(id => cfg.oauthClients.find(_.id == id)) match
        case None => err(Status.Forbidden, "連携サービスのトークン（OAuth で発行したもの）で呼んでください")
        case Some(client) => body[Json](req) { j =>
            val title = str(j, "title")
            val url   = str(j, "url").filter(u => RichText.safeUrl(u).isDefined)
            val text  = str(j, "text").getOrElse("")
            val md = List(
              title.map(t => url.fold(s"**$t**")(u => s"**[$t]($u)**")),
              Option(text).filter(_.nonEmpty),
              if title.isEmpty then url else None
            ).flatten.mkString("\n\n")
            if md.isEmpty then err(Status.BadRequest, "text か title を指定してください")
            else
              val props = Json.obj("app" -> Json.obj("id" -> client.id.asJson, "name" -> client.name.asJson, "url" -> url.asJson))
              users.bot(s"bot:app:${client.id}", client.name).flatMap { bot =>
                str(j, "channel") match
                  case Some(ch) =>
                    val target = uuidOpt(ch).fold(channels.byName(ch.stripPrefix("#")).map(_.map(_.id)))(id => IO.pure(Some(id)))
                    target.flatMap {
                      case None => err(Status.NotFound, "チャンネルが見つかりません")
                      case Some(cid) => respond(access.channel(s, cid, Need.Post))(ctx =>
                          respond(chat.publish(ctx.channel, Author(bot, "app"), RichText.fromMarkdown(md), None, false, props, "bot"))(Created(_)))
                    }
                  case None =>
                    channels.openDm(List(s.user.id, bot.id)).flatMap { dm =>
                      respond(chat.publish(dm, Author(bot, "app"), RichText.fromMarkdown(md), None, false, props, "bot")) { m =>
                        notifier.send(NotificationKind.App, s.user.id, Some(bot.id), Some(dm.id), Some(m.id), title.getOrElse(s"${client.name} からの通知"),
                          text.take(300), Paths.message(dm.id, Some(m.id), None)) *> Created(m)
                      }
                    }
              }
          })
  }
