package stui.api

import stui.core.ErrorCode

import cats.effect.*
import io.circe.Decoder
import org.http4s.*

import java.util.UUID

/** プッシュ通知（PWA）の購読（sbirds.push.PushRoutes）. 端末の登録は画面のログイン（Cookie のセッション）だけ. */
final class PushRoutes(store: sbirds.push.PushStore,
                       push: sbirds.push.PushService,
                       protected val sessions: SessionAuth,
                       protected val access: Access2) extends RouteSupport { self =>

  private val host = new sbirds.push.PushRoutes.Host {
    def authed(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] = self.authed(req)(s => f(s.user.id))
    def interactive(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
      self.sessionOnly(req)(s => f(s.user.id))
    def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] = self.body[A](req)(f)
  }

  val routes: HttpRoutes[IO] = new sbirds.push.PushRoutes(
    store,
    push,
    host,
    notAllowed = ErrorCode.PushServiceNotAllowed,
    invalid = ErrorCode.PushSubscriptionInvalid
  ).routes
}
