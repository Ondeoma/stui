package stui.api

import stui.core.ErrorCode

import cats.effect.*
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import sbirds.push.{PushService, PushStore, Subscription, WebPush}

/**
 * プッシュ通知（PWA）の購読.
 *  - `GET /api/push`: VAPID の公開鍵と、自分の購読の数
 *  - `POST /api/push/subscriptions`（PushSubscription.toJSON() の形）: この端末で受け取る
 *  - `DELETE /api/push/subscriptions`（{"endpoint": …}）: この端末で受け取るのをやめる
 */
final class PushRoutes(store: PushStore,
                       push: PushService,
                       protected val sessions: SessionAuth,
                       protected val access: Access2) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "push" => getPush(req)
    case req @ POST -> Root / "api" / "push" / "subscriptions" => postPushSubscriptions(req)
    case req @ DELETE -> Root / "api" / "push" / "subscriptions" => deletePushSubscriptions(req)
  }

  private def getPush(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      store.count(s.user.id).flatMap(n =>
        Ok(Json.obj("publicKey" -> Json.fromString(push.publicKey), "subscriptions" -> Json.fromInt(n)))
      )
    )

  private def postPushSubscriptions(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      body[SubscribeReq](req) { r =>
        if !WebPush.allowedEndpoint(r.endpoint) then err(ErrorCode.PushServiceNotAllowed)
        else if r.keys.p256dh.length > 200 || r.keys.auth.length > 100 || r.endpoint.length > 2000 then
          err(ErrorCode.PushSubscriptionInvalid)
        else
          store.subscribe(
            s.user.id,
            Subscription(r.endpoint, r.keys.p256dh, r.keys.auth),
            req.headers.get(CIString("User-Agent")).map(_.head.value.take(300))
          ) *> NoContent()
      }
    )

  private def deletePushSubscriptions(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[EndpointReq](req)(r => store.unsubscribe(s.user.id, r.endpoint) *> NoContent()))

  private final case class Keys(p256dh: String, auth: String) derives Decoder
  private final case class SubscribeReq(endpoint: String, keys: Keys) derives Decoder
  private final case class EndpointReq(endpoint: String) derives Decoder

}
