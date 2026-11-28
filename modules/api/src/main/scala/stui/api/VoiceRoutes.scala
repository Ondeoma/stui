package stui.api

import cats.effect.*
import org.http4s.*
import stui.core.*
import stui.shared.*

/** ボイスチャンネル（入る・出る・状態・接続の取り決め）. 画面（Cookie のセッション）からだけ使う. */
final class VoiceRoutes(voice: VoiceService, val sessions: SessionAuth, val access: Access2) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "voice" => getVoice(req)
    case req @ POST -> Root / "api" / "voice" / "join" => postJoin(req)
    case req @ POST -> Root / "api" / "voice" / "leave" => postLeave(req)
    case req @ POST -> Root / "api" / "voice" / "state" => postState(req)
    case req @ POST -> Root / "api" / "voice" / "signal" => postSignal(req)
  }

  /** いま入っている人（参加しているチャンネルと、watch で指定した見ている公開チャンネル）. */
  private def getVoice(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => okJson(voice.rooms(s, param(req, "watch").flatMap(uuidOpt))))

  private def postJoin(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => body[VoiceJoinReq](req)(r => okJson(voice.join(s, r))))

  private def postLeave(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => body[VoiceLeaveReq](req)(r => voice.leave(s, r) *> NoContent()))

  private def postState(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => body[VoiceStateReq](req)(r => respond(voice.update(s, r))(_ => NoContent())))

  private def postSignal(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => body[VoiceSignalReq](req)(r => respond(voice.signal(s, r))(_ => NoContent())))
}
