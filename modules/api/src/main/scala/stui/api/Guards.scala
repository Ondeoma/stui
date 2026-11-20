package stui.api

import cats.effect.*
import org.http4s.*
import sbirds.http.{BodyLimit, Csrf, SecurityHeaders}
import sbirds.shared.AppError
import stui.core.ErrorCode

/**
 * すべての要求に共通の守り:
 *  - 本文の大きさの上限（アップロードは MAX_UPLOAD_MB、それ以外は 2MB）。大きすぎる本文でメモリを使い切らせない。
 *  - セキュリティのヘッダー（iframe の禁止・nosniff・Referrer・https なら HSTS）。
 *  - Cookie のセッションで状態を変える要求は、別のサイト（Origin が違う）からなら断る（同じ登録ドメインの別のサービスからの CSRF も防ぐ）。
 */
object Guards {

  private val JsonLimit: Long = 2L * 1024 * 1024
  private def isUpload(req: Request[IO]): Boolean = {
    val p = req.pathInfo.renderString
    req.method == Method.POST &&
    (p.matches("^/api/channels/[^/]+/files$") || p == "/api/me/avatar" || p == "/api/emoji")
  }

  def apply(app: HttpRoutes[IO], cfg: ApiConfig): HttpRoutes[IO] = {
    // Origin（なければ Referer）と Sec-Fetch-Site を確かめる. "null"（サンドボックスの iframe など）も別のサイトとして扱う
    val csrf = Csrf.Policy(cfg.publicUrl, devLocalhost = cfg.devLogin).copy(safeMethods = Csrf.SafeWithOptions)
    // 本文を読む途中で上限を超えたとき（Content-Length がない要求など）も 413 にする
    val limited = BodyLimit(
      req => Some(if isUpload(req) then cfg.maxUploadBytes + 1024 * 1024 else JsonLimit),
      (_, _) => AppError(ErrorCode.RequestTooLarge)
    )(app)
    // すべての応答に付けるセキュリティのヘッダー（応答が自分で付けたものは残す）。iframe に入れさせない（クリックジャッキング）。
    val headers = SecurityHeaders.Policy(
      hsts = SecurityHeaders.hsts(cfg.publicUrl.startsWith("https://")),
      referrer = Some("same-origin"),
      csp = Some(SecurityHeaders.FrameCsp),
      keepExisting = true
    )
    SecurityHeaders(headers)(Csrf.routes(csrf, Csrf.cookieAuth(cfg.cookieName), ErrorCode.CrossSiteRequest)(limited))
  }
}
