package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import stui.auth.Crypto
import stui.core.*
import stui.db.*
import stui.shared.*

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration.*

/**
 * Webhook。受信は Slack・Mattermost と同じ形の JSON（text・username・icon_url・attachments）でチャンネルに投稿する。
 * 送信はイベントを JSON で POST し（HMAC-SHA256 の署名付き）、応答に text があればボットとして返信する。
 */
final class WebhookService(hooks: HookStore, users: UserStore, channels: ChannelStore, chat: ChatService, http: Client[IO], cfg: ApiConfig):

  def validUrl(u: String): Either[Fail, String] =
    val t = u.trim
    if t.startsWith("https://") || (cfg.webhookAllowHttp && t.startsWith("http://")) then
      Uri.fromString(t).left.map(_ => Fail.Invalid("URL が正しくありません")).map(_ => t)
    else Left(Fail.Invalid(if cfg.webhookAllowHttp then "URL は http(s):// で始めてください" else "URL は https:// で始めてください"))

  private def str(c: ACursor, k: String): Option[String] = c.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  /** 受信した JSON を Markdown にする（attachments は見出し・本文・項目を並べる）。 */
  def toMarkdown(j: Json): String =
    val c = j.hcursor
    val atts = c.downField("attachments").focus.flatMap(_.asArray).toList.flatten.map { a =>
      val ac = a.hcursor
      val title = (str(ac, "title"), str(ac, "title_link")) match
        case (Some(t), Some(l)) => Some(s"**[$t]($l)**")
        case (Some(t), None)    => Some(s"**$t**")
        case _                  => None
      val fields = ac.downField("fields").focus.flatMap(_.asArray).toList.flatten.flatMap(f =>
        (str(f.hcursor, "title"), str(f.hcursor, "value")).mapN((t, v) => s"- **$t**: $v"))
      (List(str(ac, "pretext"), str(ac, "author_name"), title, str(ac, "text").orElse(if title.isEmpty then str(ac, "fallback") else None)).flatten ++
        fields ++ str(ac, "footer").toList).mkString("\n\n")
    }
    (str(c, "text").toList ++ atts).mkString("\n\n")

  /** 受信の Webhook の投稿。 */
  def receive(h: IncomingRow, payload: Json): Res[MessageDto] =
    val md = toMarkdown(payload)
    if md.trim.isEmpty then IO.pure(Left(Fail.Invalid("text がありません")))
    else
      channels.find(h.channelId).flatMap {
        case None => IO.pure(Left(Fail.NotFound("チャンネルが見つかりません")))
        case Some(c) if c.archived => IO.pure(Left(Fail.Forbidden("チャンネルはアーカイブされています")))
        case Some(c) =>
          val c0 = payload.hcursor
          val name = str(c0, "username").orElse(h.username).getOrElse(h.name).take(80)
          val icon = str(c0, "icon_url").orElse(h.iconUrl).filter(_.startsWith("https://"))
          // 送られてきたアイコン（画像・絵文字）が優先。なければ Webhook に選んだもの
          val sentIcon = str(c0, "icon_url").orElse(str(c0, "icon_emoji"))
          val emoji = str(c0, "icon_emoji").filter(_.length <= 40).orElse(if sentIcon.isEmpty && h.iconUrl.isEmpty then h.iconEmoji else None)
          users.bot(s"bot:hook:${h.id}", h.name).flatMap(bot =>
            chat.publish(c, Author(bot, "webhook"), RichText.fromMarkdown(md), None, false,
              Json.obj("username" -> name.asJson, "iconUrl" -> icon.asJson, "iconEmoji" -> emoji.asJson, "hook" -> h.id.asJson).dropNullValues, "bot"))
      }

  /** 送信の応答に text があれば返信する（response_type が comment ならスレッドに）。 */
  private def reply(d: DeliveryRow, body: String): IO[Unit] =
    parse(body).toOption.filter(_ => d.event == EventKind.MessageCreated).traverse_ { j =>
      val c = j.hcursor
      str(c, "text").traverse_ { text =>
        val channelId = d.payload.hcursor.downField("channel").get[UUID]("id").toOption
        val msg       = d.payload.hcursor.downField("message")
        val thread    = msg.get[Option[UUID]]("rootId").toOption.flatten
          .orElse(Option.when(str(c, "response_type").contains("comment"))(msg.get[UUID]("id").toOption).flatten)
        channelId.flatTraverse(channels.find).flatMap(_.traverse_ { ch =>
          hooks.outgoingRow(d.webhookId).flatMap(_.traverse_ { h =>
            users.bot(s"bot:outhook:${h.id}", h.name).flatMap(bot =>
              chat.publish(ch, Author(bot, "webhook"), RichText.fromMarkdown(text), thread, false,
                Json.obj("username" -> str(c, "username").getOrElse(h.name).take(80).asJson,
                  "iconUrl" -> str(c, "icon_url").filter(_.startsWith("https://")).asJson, "hook" -> h.id.asJson)
                  .dropNullValues, "bot").void)
          })
        })
      }
    }.handleErrorWith(e => IO.println(s"[stui] webhook reply failed: $e"))

  /**
   * 送信先が内部のネットワーク（ループバック・プライベート・リンクローカルなど）でないか。名前を引いた結果のすべての IP で確かめる。
   * 開発では WEBHOOK_ALLOW_PRIVATE=true で許す。
   */
  def publicHost(url: String): IO[Boolean] =
    if cfg.webhookAllowPrivate then IO.pure(true)
    else
      Uri.fromString(url).toOption.flatMap(_.host).map(_.value.stripPrefix("[").stripSuffix("]")) match
        case None => IO.pure(false)
        case Some(h) =>
          IO.blocking(java.net.InetAddress.getAllByName(h).toList).map(_.forall { a =>
            val b = a.getAddress
            val ula = b.length == 16 && (b(0) & 0xfe) == 0xfc // fc00::/7
            val cgnat = b.length == 4 && (b(0) & 0xff) == 100 && (b(1) & 0xc0) == 64 // 100.64.0.0/10
            !(a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress || ula || cgnat)
          }).handleError(_ => false)

  def send(d: DeliveryRow): IO[(OutboxResult, Option[Int])] =
    publicHost(d.url).flatMap {
      case false => IO.pure((OutboxResult.Skip("送信先が内部のネットワークのため送りませんでした"), None))
      case true  => deliver(d)
    }

  private def deliver(d: DeliveryRow): IO[(OutboxResult, Option[Int])] =
    val body = d.payload.noSpaces
    val sig  = "sha256=" + Crypto.hmacHex(d.secret, body.getBytes(StandardCharsets.UTF_8))
    val req = Request[IO](Method.POST, Uri.unsafeFromString(d.url))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
      .putHeaders("X-Stui-Event" -> d.event, "X-Stui-Delivery" -> d.id.toString, "X-Stui-Signature" -> sig, "User-Agent" -> "stui-webhook")
    http.run(req).use(r => r.body.take(65536).through(fs2.text.utf8.decode).compile.string.map(r.status.code -> _)).timeout(15.seconds).attempt.flatMap {
      case Right((c, text)) if c >= 200 && c < 300 => reply(d, text).as((OutboxResult.Sent, Some(c)))
      case Right((c, _)) if c == 410               => IO.pure((OutboxResult.Skip(s"HTTP $c"), Some(c)))
      case Right((c, _))                           => IO.pure((OutboxResult.Retry(s"HTTP $c"), Some(c)))
      // 中身（内部の様子がわかる文言）は記録しない
      case Left(_: java.util.concurrent.TimeoutException) => IO.pure((OutboxResult.Retry("時間切れ"), None))
      case Left(_)                                 => IO.pure((OutboxResult.Retry("接続できませんでした"), None))
    }

  def loop: IO[Nothing] =
    Periodic.run(10.seconds, 5.seconds)(e => s"[stui] webhook worker failed: $e")(hooks.processDeliveries(50)(send))

  def purgeLoop: IO[Nothing] =
    Periodic.run(1.hour, 24.hours)(e => s"[stui] webhook purge failed: $e")(hooks.purgeDeliveries(30))

/** Webhook の管理と受信。 */
final class HookRoutes(hooks: HookStore, svc: WebhookService, channels: ChannelStore, activity: ActivityStore, val sessions: SessionAuth,
    val access: Access2, cfg: ApiConfig) extends RouteSupport:

  private def incomingUrl(token: String): String = s"${cfg.publicUrl}/hooks/$token"

  /** 自分が管理できるチャンネル（システム管理者は None = すべて）。 */
  private def manageable(s: AuthSession): IO[Option[List[UUID]]] =
    if s.canAdmin then IO.pure(None)
    else channels.listForUser(s.user.id).map(cs => Some(cs.filter(_.role.contains(ChannelRole.Admin)).map(_.id)))

  private def validName(n: String): Option[String] = Option.when(n.trim.isEmpty)("名前を入力してください")

  private def validIncoming(r: IncomingHookReq): Option[String] =
    validName(r.name).orElse(r.iconEmoji.filterNot(HookIcons.valid).map(_ => "選べるアイコンから選んでください"))

  /** 受信の Webhook の本文（JSON、または payload=JSON のフォーム）。 */
  private def payload(req: Request[IO]): IO[Option[Json]] =
    req.bodyText.compile.string.map { t =>
      parse(t).toOption.orElse(
        UrlForm.decodeString(Charset.`UTF-8`)(t).toOption.flatMap(_.getFirst("payload")).flatMap(p => parse(p).toOption))
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- 受信（トークンだけで投稿できる）
    case req @ POST -> Root / "hooks" / token =>
      hooks.incomingByToken(Crypto.sha256(token)).flatMap {
        case Some(h) if h.enabled =>
          payload(req).flatMap {
            case None => err(Status.BadRequest, "JSON（または payload=JSON のフォーム）で送ってください")
            case Some(j) => respond(svc.receive(h, j))(m => Ok(Json.obj("ok" -> true.asJson, "id" -> m.id.asJson)))
          }
        case _ => err(Status.NotFound, "Webhook が見つかりません")
      }

    // ---------------------------------------------------------------- 受信の Webhook の管理
    case req @ GET -> Root / "api" / "hooks" / "incoming" =>
      authed(req)(s => manageable(s).flatMap { ids =>
        val filter = param(req, "channelId").flatMap(uuidOpt).map(c => ids.fold(List(c))(_.filter(_ == c)))
        hooks.incoming(filter.orElse(ids)).flatMap(Ok(_))
      })

    case req @ POST -> Root / "api" / "hooks" / "incoming" =>
      authed(req)(s => body[IncomingHookReq](req)(r => validIncoming(r) match
        case Some(e) => err(Status.BadRequest, e)
        case None =>
          respond(access.channel(s, r.channelId, Need.Manage)) { _ =>
            val token = Crypto.randomToken(24)
            for
              id <- hooks.createIncoming(r.copy(name = r.name.trim), Crypto.sha256(token), token.take(6), s.user.id)
              _  <- activity.audit(Some(s.user.id), s.viaString, "webhook.incoming.create", Some(id.toString), Json.obj("name" -> r.name.asJson))
              h  <- hooks.incomingDto(id)
              res <- Created(CreatedIncomingHookDto(h.get, incomingUrl(token)))
            yield res
          }))

    case req @ PUT -> Root / "api" / "hooks" / "incoming" / UuidVar(id) =>
      authed(req)(s => body[IncomingHookReq](req)(r => hooks.incomingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(_) if validIncoming(r).isDefined => err(Status.BadRequest, validIncoming(r).get)
        case Some(h) =>
          respond(access.channel(s, h.channelId, Need.Manage))(_ => respond(access.channel(s, r.channelId, Need.Manage))(_ =>
            hooks.updateIncoming(id, r.copy(name = r.name.trim)) *> hooks.incomingDto(id).flatMap(d => Ok(d.get))))
      }))

    /** トークンを作り直す（前の URL は使えなくなる）。 */
    case req @ POST -> Root / "api" / "hooks" / "incoming" / UuidVar(id) / "token" =>
      authed(req)(s => hooks.incomingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(h) =>
          respond(access.channel(s, h.channelId, Need.Manage)) { _ =>
            val token = Crypto.randomToken(24)
            hooks.deleteIncoming(id) *>
              hooks.createIncoming(IncomingHookReq(h.channelId, h.name, h.username, h.iconUrl, h.enabled, h.iconEmoji), Crypto.sha256(token), token.take(6), s.user.id)
                .flatMap(nid => hooks.incomingDto(nid).flatMap(d => Ok(CreatedIncomingHookDto(d.get, incomingUrl(token)))))
          }
      })

    case req @ DELETE -> Root / "api" / "hooks" / "incoming" / UuidVar(id) =>
      authed(req)(s => hooks.incomingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(h) => respond(access.channel(s, h.channelId, Need.Manage))(_ =>
            hooks.deleteIncoming(id) *> activity.audit(Some(s.user.id), s.viaString, "webhook.incoming.delete", Some(id.toString), Json.obj()) *> NoContent())
      })

    // ---------------------------------------------------------------- 送信の Webhook の管理
    case req @ GET -> Root / "api" / "hooks" / "outgoing" =>
      authed(req)(s => manageable(s).flatMap(ids => hooks.outgoing(ids).flatMap(Ok(_))))

    case req @ POST -> Root / "api" / "hooks" / "outgoing" =>
      authed(req)(s => body[OutgoingHookReq](req)(r => checkOutgoing(s, r) {
        val secret = Crypto.randomToken(24)
        hooks.createOutgoing(r.copy(name = r.name.trim, url = r.url.trim), secret, s.user.id).flatTap(id =>
          activity.audit(Some(s.user.id), s.viaString, "webhook.outgoing.create", Some(id.toString), Json.obj("name" -> r.name.asJson))) *>
          manageable(s).flatMap(ids => hooks.outgoing(ids).flatMap(Created(_)))
      }))

    case req @ PUT -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) =>
      authed(req)(s => body[OutgoingHookReq](req)(r => hooks.outgoingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(h) => checkOutgoing(s, OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled))(checkOutgoing(s, r)(
            hooks.updateOutgoing(id, r.copy(name = r.name.trim, url = r.url.trim)) *> NoContent()))
      }))

    case req @ DELETE -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) =>
      authed(req)(s => hooks.outgoingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(h) => checkOutgoing(s, OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled))(
            hooks.deleteOutgoing(id) *> activity.audit(Some(s.user.id), s.viaString, "webhook.outgoing.delete", Some(id.toString), Json.obj()) *> NoContent())
      })

    case req @ GET -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) / "deliveries" =>
      authed(req)(s => hooks.outgoingRow(id).flatMap {
        case None => err(Status.NotFound, "Webhook が見つかりません")
        case Some(h) => checkOutgoing(s, OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled))(hooks.deliveries(id, 50).flatMap(Ok(_)))
      })
  }

  /** 送信の Webhook を扱えるか（全体のものはシステム管理者、チャンネルのものはそのチャンネルの管理者）。 */
  private def checkOutgoing(s: AuthSession, r: OutgoingHookReq)(f: => IO[Response[IO]]): IO[Response[IO]] =
    svc.publicHost(r.url).flatMap(ok => if !ok then err(Status.BadRequest, "内部のネットワークの URL には送れません") else checkOutgoing0(s, r)(f))

  private def checkOutgoing0(s: AuthSession, r: OutgoingHookReq)(f: => IO[Response[IO]]): IO[Response[IO]] =
    validName(r.name).orElse(svc.validUrl(r.url).left.toOption.map(_.message))
      .orElse(r.events.find(e => !EventKind.webhook.contains(e)).map(e => s"使えないイベントです: $e")) match
      case Some(e) => err(Status.BadRequest, e)
      case None => r.channelId match
          case None => if s.canAdmin then f else err(Status.Forbidden, "全体の Webhook はシステム管理者だけが扱えます")
          case Some(c) => respond(access.channel(s, c, Need.Manage))(ctx =>
              if ctx.channel.isDm then err(Status.BadRequest, "DM には設定できません") else f)
