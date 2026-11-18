package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.util.UUID

/**
 * エージェント専用アカウント（管理画面）。skakapo などの外部の AI エージェントが MCP・REST API を呼ぶための、ログインしないアカウント。
 * 作成・名前の変更・無効化・APIキーの発行と取り消し・チャンネルへの参加。システム管理者が画面（Cookie のセッション）からだけ操作できる。
 * エージェントは自分の権限で動く（公開チャンネルは自分で参加できる。非公開チャンネルはメンバーが追加するか、ここから参加させる）。
 */
final class AgentRoutes(users: UserStore, channels: ChannelStore, chat: ChatService, activity: ActivityStore, val sessions: SessionAuth,
    val access: Access2) extends RouteSupport:

  /** エージェント専用アカウントのキーには admin スコープを付けない。 */
  private val NoAdminScope = Fail.Invalid("エージェント専用アカウントのキーには admin スコープを付けられません")

  private def withAgent(req: Request[IO], id: UUID)(f: (AuthSession, User) => IO[Response[IO]]): IO[Response[IO]] =
    adminSession(req)(s => users.findAgent(id).flatMap {
      case None    => err(Status.NotFound, "エージェント専用アカウントが見つかりません")
      case Some(a) => f(s, a)
    })

  private def dto(id: UUID): IO[Response[IO]] =
    users.agents.map(_.find(_.id == id)).flatMap(_.fold(err(Status.NotFound, "エージェント専用アカウントが見つかりません"))(Ok(_)))

  private def audit(s: AuthSession, action: String, target: UUID, detail: Json = Json.obj()): IO[Unit] =
    activity.audit(Some(s.user.id), s.viaString, action, Some(target.toString), detail)

  /** 参加・退出の対象のチャンネル（公開・非公開だけ。DM は見つからない扱い）。 */
  private def channelOf(id: UUID, agent: UUID): IO[Option[ChannelRow]] =
    channels.access(id, agent).map(_.map(_._1).filterNot(_.isDm))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "admin" / "agents" => adminSession(req)(_ => users.agents.flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "admin" / "agents" =>
      adminSession(req)(s => body[CreateAgentReq](req)(r =>
        AgentAccounts.validName(r.name) match
          case Left(m) => err(Status.BadRequest, m)
          case Right(name) =>
            users.createAgent(name).flatMap { a =>
              audit(s, "agent.create", a.id, Json.obj("name" -> name.asJson)) *>
                users.agents.map(_.find(_.id == a.id)).flatMap(_.fold(err(Status.InternalServerError, "作成できませんでした"))(Created(_)))
            }))

    case req @ PATCH -> Root / "api" / "admin" / "agents" / UuidVar(id) =>
      withAgent(req, id)((s, a) => body[UpdateAgentReq](req)(r =>
        r.name.traverse(AgentAccounts.validName) match
          case Left(m) => err(Status.BadRequest, m)
          case Right(name) =>
            users.updateMe(a.id, name, None) *> users.update(a.id, None, r.disabled) *>
              audit(s, "agent.update", a.id, Json.obj("name" -> name.asJson, "disabled" -> r.disabled.asJson)) *> dto(a.id)))

    case req @ POST -> Root / "api" / "admin" / "agents" / UuidVar(id) / "keys" =>
      withAgent(req, id)((s, a) => body[CreateApiKeyReq](req)(r =>
        respond(ApiKeyIssue(users, a.id, r, Some(NoAdminScope))) { c =>
          audit(s, "agent.api_key.create", a.id, Json.obj("key" -> c.info.id.asJson, "name" -> c.info.name.asJson, "scopes" -> c.info.scopes.asJson)) *>
            Created(c)
        }))

    case req @ DELETE -> Root / "api" / "admin" / "agents" / UuidVar(id) / "keys" / UuidVar(keyId) =>
      withAgent(req, id)((s, a) => users.revokeKey(a.id, keyId).flatMap {
        case true  => audit(s, "agent.api_key.revoke", a.id, Json.obj("key" -> keyId.asJson)) *> NoContent()
        case false => err(Status.NotFound, "APIキーが見つかりません")
      })

    // チャンネルに参加させる（非公開チャンネルにも。管理者自身がメンバーでなくてよい）
    case req @ POST -> Root / "api" / "admin" / "agents" / UuidVar(id) / "channels" =>
      withAgent(req, id)((s, a) => body[AgentChannelsReq](req) { r =>
        val ids = r.channelIds.distinct
        if ids.isEmpty then err(Status.BadRequest, "チャンネルを選んでください")
        else if ids.size > 100 then err(Status.BadRequest, "一度に選べるのは100チャンネルまでです")
        else ids.traverse(channelOf(_, a.id)).flatMap { found =>
          found.sequence match
            case None => err(Status.NotFound, "チャンネルが見つかりません")
            case Some(cs) if cs.exists(_.archived) => err(Status.Forbidden, "アーカイブしたチャンネルには参加させられません")
            case Some(cs) =>
              cs.traverse_(c => chat.addMembersTo(c, s.user, List(a.id))) *>
                audit(s, "agent.channel.join", a.id, Json.obj("channels" -> cs.map(_.name).asJson)) *> dto(a.id)
        }
      })

    case req @ DELETE -> Root / "api" / "admin" / "agents" / UuidVar(id) / "channels" / UuidVar(cid) =>
      withAgent(req, id)((s, a) => channelOf(cid, a.id).flatMap {
        case None => err(Status.NotFound, "チャンネルが見つかりません")
        case Some(c) =>
          respond(chat.removeMemberFrom(c, s.user, a.id))(_ => audit(s, "agent.channel.leave", a.id, Json.obj("channel" -> c.name.asJson)) *> dto(a.id))
      })
  }
