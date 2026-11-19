package stui.api

import cats.effect.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import stui.core.*
import stui.db.*
import stui.shared.*

import java.util.UUID

/** Webhook の管理と受信。 */
final class HookRoutes(
  hooks: HookStore,
  svc: WebhookService,
  channels: ChannelStore,
  activity: ActivityStore,
  val sessions: SessionAuth,
  val access: Access2,
  cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- 受信（トークンだけで投稿できる）
    case req @ POST -> Root / "hooks" / token => postHookByToken(req, token)
    case req @ GET -> Root / "api" / "hooks" / "incoming" => getHooksIncoming(req)
    case req @ POST -> Root / "api" / "hooks" / "incoming" => postHooksIncoming(req)
    case req @ PUT -> Root / "api" / "hooks" / "incoming" / UuidVar(id) => putHooksIncoming(req, id)
    case req @ POST -> Root / "api" / "hooks" / "incoming" / UuidVar(id) / "token" => postHooksIncomingToken(req, id)
    case req @ DELETE -> Root / "api" / "hooks" / "incoming" / UuidVar(id) => deleteHooksIncoming(req, id)
    case req @ GET -> Root / "api" / "hooks" / "outgoing" => getHooksOutgoing(req)
    case req @ POST -> Root / "api" / "hooks" / "outgoing" => postHooksOutgoing(req)
    case req @ PUT -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) => putHooksOutgoing(req, id)
    case req @ DELETE -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) => deleteHooksOutgoing(req, id)
    case req @ GET -> Root / "api" / "hooks" / "outgoing" / UuidVar(id) / "deliveries" =>
      getHooksOutgoingDeliveries(req, id)
  }

  private def postHookByToken(
    req: Request[IO],
    token: String
  ): IO[Response[IO]] =
    hooks.incomingByToken(Crypto.sha256(token)).flatMap {
      case Some(h) if h.enabled =>
        payload(req).flatMap {
          case None => err(ErrorCode.WebhookPayloadInvalid)
          case Some(j) => respond(svc.receive(h, j))(m => Ok(Json.obj("ok" -> true.asJson, "id" -> m.id.asJson)))
        }
      case _ => err(ErrorCode.WebhookNotFound)
    }

  private def getHooksIncoming(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      manageable(s).flatMap { ids =>
        val filter = param(req, "channelId").flatMap(uuidOpt).map(c => ids.fold(List(c))(_.filter(_ == c)))
        hooks.incoming(filter.orElse(ids)).flatMap(Ok(_))
      }
    )

  private def postHooksIncoming(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[IncomingHookReq](req)(r =>
        validIncoming(r) match {
          case Some(e) => err(ErrorCode.WebhookInvalid, e)
          case None =>
            respond(access.channel(s, r.channelId, Need.Manage)) { _ =>
              val token = Crypto.randomToken(24)
              for {
                id <- hooks.createIncoming(r.copy(name = r.name.trim), Crypto.sha256(token), token.take(6), s.user.id)
                _ <- activity.audit(
                  Some(s.user.id),
                  s.viaString,
                  "webhook.incoming.create",
                  Some(id.toString),
                  Json.obj("name" -> r.name.asJson)
                )
                h <- hooks.incomingDto(id)
                res <- Created(CreatedIncomingHookDto(h.get, incomingUrl(token)))
              } yield res
            }
        }
      )
    )

  private def putHooksIncoming(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[IncomingHookReq](req)(r =>
        hooks.incomingRow(id).flatMap {
          case None => err(ErrorCode.WebhookNotFound)
          case Some(_) if validIncoming(r).isDefined => err(ErrorCode.WebhookInvalid, validIncoming(r).get)
          case Some(h) =>
            respond(access.channel(s, h.channelId, Need.Manage))(_ =>
              respond(access.channel(s, r.channelId, Need.Manage))(_ =>
                hooks.updateIncoming(id, r.copy(name = r.name.trim)) *> hooks.incomingDto(id).flatMap(d => Ok(d.get))
              )
            )
        }
      )
    )

  private def postHooksIncomingToken(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      hooks.incomingRow(id).flatMap {
        case None => err(ErrorCode.WebhookNotFound)
        case Some(h) =>
          respond(access.channel(s, h.channelId, Need.Manage)) { _ =>
            val token = Crypto.randomToken(24)
            hooks.deleteIncoming(id) *>
              hooks.createIncoming(
                IncomingHookReq(h.channelId, h.name, h.username, h.iconUrl, h.enabled, h.iconEmoji),
                Crypto.sha256(token),
                token.take(6),
                s.user.id
              )
                .flatMap(nid =>
                  hooks.incomingDto(nid).flatMap(d => Ok(CreatedIncomingHookDto(d.get, incomingUrl(token))))
                )
          }
      }
    )

  private def deleteHooksIncoming(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      hooks.incomingRow(id).flatMap {
        case None => err(ErrorCode.WebhookNotFound)
        case Some(h) => respond(access.channel(s, h.channelId, Need.Manage))(_ =>
            hooks.deleteIncoming(id) *> activity.audit(
              Some(s.user.id),
              s.viaString,
              "webhook.incoming.delete",
              Some(id.toString),
              Json.obj()
            ) *> NoContent()
          )
      }
    )

  private def getHooksOutgoing(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => manageable(s).flatMap(ids => hooks.outgoing(ids).flatMap(Ok(_))))

  private def postHooksOutgoing(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[OutgoingHookReq](req)(r =>
        checkOutgoing(s, r) {
          val secret = Crypto.randomToken(24)
          hooks.createOutgoing(r.copy(name = r.name.trim, url = r.url.trim), secret, s.user.id).flatTap(id =>
            activity.audit(
              Some(s.user.id),
              s.viaString,
              "webhook.outgoing.create",
              Some(id.toString),
              Json.obj("name" -> r.name.asJson)
            )
          ) *>
            manageable(s).flatMap(ids => hooks.outgoing(ids).flatMap(Created(_)))
        }
      )
    )

  private def putHooksOutgoing(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[OutgoingHookReq](req)(r =>
        hooks.outgoingRow(id).flatMap {
          case None => err(ErrorCode.WebhookNotFound)
          case Some(h) =>
            checkOutgoing(s, OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled))(checkOutgoing(s, r)(
              hooks.updateOutgoing(id, r.copy(name = r.name.trim, url = r.url.trim)) *> NoContent()
            ))
        }
      )
    )

  private def deleteHooksOutgoing(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      hooks.outgoingRow(id).flatMap {
        case None => err(ErrorCode.WebhookNotFound)
        case Some(h) => checkOutgoing(s, OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled))(
            hooks.deleteOutgoing(id) *> activity.audit(
              Some(s.user.id),
              s.viaString,
              "webhook.outgoing.delete",
              Some(id.toString),
              Json.obj()
            ) *> NoContent()
          )
      }
    )

  private def getHooksOutgoingDeliveries(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      hooks.outgoingRow(id).flatMap {
        case None => err(ErrorCode.WebhookNotFound)
        case Some(h) => checkOutgoing(
            s,
            OutgoingHookReq(h.channelId, h.name, h.url, Nil, Nil, h.enabled)
          )(hooks.deliveries(id, 50).flatMap(Ok(_)))
      }
    )

  private def incomingUrl(
    token: String
  ): String = s"${cfg.publicUrl}/hooks/$token"

  /** 自分が管理できるチャンネル（システム管理者は None = すべて）。 */
  private def manageable(
    s: AuthSession
  ): IO[Option[List[UUID]]] =
    if s.canAdmin then IO.pure(None)
    else channels.listForUser(s.user.id).map(cs => Some(cs.filter(_.role.contains(ChannelRole.Admin)).map(_.id)))

  private def validName(
    n: String
  ): Option[String] = Option.when(n.trim.isEmpty)("名前を入力してください")

  private def validIncoming(
    r: IncomingHookReq
  ): Option[String] =
    validName(r.name).orElse(r.iconEmoji.filterNot(HookIcons.valid).map(_ => "選べるアイコンから選んでください"))

  /** 受信の Webhook の本文（JSON、または payload=JSON のフォーム）。 */
  private def payload(
    req: Request[IO]
  ): IO[Option[Json]] =
    req.bodyText.compile.string.map { t =>
      parse(t).toOption.orElse(
        UrlForm.decodeString(Charset.`UTF-8`)(t).toOption.flatMap(_.getFirst("payload")).flatMap(p => parse(p).toOption)
      )
    }

  /** 送信の Webhook を扱えるか（全体のものはシステム管理者、チャンネルのものはそのチャンネルの管理者）。 */
  private def checkOutgoing(
    s: AuthSession,
    r: OutgoingHookReq
  )(
    f: => IO[Response[IO]]
  ): IO[Response[IO]] =
    svc.publicHost(r.url).flatMap(ok =>
      if !ok then err(ErrorCode.WebhookPrivateNetwork) else checkOutgoing0(s, r)(f)
    )

  private def checkOutgoing0(
    s: AuthSession,
    r: OutgoingHookReq
  )(
    f: => IO[Response[IO]]
  ): IO[Response[IO]] =
    validName(r.name).orElse(svc.validUrl(r.url).left.toOption.map(_.message))
      .orElse(r.events.find(e => !EventKind.webhook.contains(e)).map(e => s"使えないイベントです: $e")) match {
      case Some(e) => err(ErrorCode.WebhookInvalid, e)
      case None => r.channelId match {
          case None => if s.canAdmin then f else err(ErrorCode.WebhookGlobalAdminOnly)
          case Some(c) => respond(access.channel(s, c, Need.Manage))(ctx =>
              if ctx.channel.isDm then err(ErrorCode.WebhookDmNotAllowed) else f
            )
        }
    }
}
