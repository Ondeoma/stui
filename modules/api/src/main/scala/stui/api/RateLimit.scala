package stui.api

import cats.effect.*
import org.http4s.*
import sbirds.http.RateLimit.Path
import sbirds.http.RateLimitConfig
import stui.core.ErrorCode

import scala.concurrent.duration.FiniteDuration

/**
 * stui の数え方（どの入口をどのカウンタで数えるか）.
 * 共通の数え方（/auth/…・/oauth/authorize は auth、/oauth/token・/oauth/revoke・/api/public/… は public の枠）は
 * sbirds-lib の RateLimit.standard. ここでは、stui だけの違いを決める.
 * 受信の Webhook（/hooks/{token}）は、CI や監視が同じ IP から大量に送るのが正常なので、数えない（トークンで確かめている）.
 */
object RateLimit {

  def apply(routes: HttpRoutes[IO],
            cfg: RateLimitConfig,
            clock: IO[FiniteDuration] = IO.realTime): HttpRoutes[IO] =
    sbirds.http.RateLimit.standard(routes, cfg, ErrorCode.TooManyRequests, clock) { b =>
      { case Path("hooks" :: _) => b.skip }
    }
}
