package stui.api

import munit.CatsEffectSuite

/** 送信の Webhook の宛先の確認（SSRF を防ぐ）。publicHost は設定だけを使うので、ほかの依存は渡さない。 */
class WebhookSpec extends CatsEffectSuite:

  private def svc(allowPrivate: Boolean) =
    new WebhookService(null, null, null, null, null, ApiConfig(Set.empty, false, "https://stui.example.com", webhookAllowPrivate = allowPrivate))

  test("内部のネットワークの宛先は断る") {
    val s = svc(allowPrivate = false)
    for
      lo  <- s.publicHost("https://127.0.0.1/hook")
      pr  <- s.publicHost("https://10.0.0.5:8443/x")
      md  <- s.publicHost("http://169.254.169.254/latest/meta-data")
      v6  <- s.publicHost("https://[::1]/x")
      ula <- s.publicHost("https://[fd00::1]/x")
      ok  <- s.publicHost("https://93.184.216.34/hook")
    yield
      assert(!lo && !pr && !md && !v6 && !ula)
      assert(ok)
  }

  test("開発では WEBHOOK_ALLOW_PRIVATE で許す") {
    svc(allowPrivate = true).publicHost("http://localhost:9/hook").map(assert(_))
  }

  test("受信の JSON を Markdown にする（attachments も）") {
    val j = io.circe.parser.parse("""{"text":"本文","attachments":[{"title":"題","title_link":"https://x.example","fields":[{"title":"時間","value":"3分"}]}]}""").toOption.get
    assertEquals(stui.core.SlackPayload.toMarkdown(j), "本文\n\n**[題](https://x.example)**\n\n- **時間**: 3分")
  }

  test("送る本文は payload そのもので、署名もその本文で合う（JSON の文字列にしない）") {
    val payload = io.circe.parser.parse("""{"event":"message.created","text":"改行\nと\"引用符\""}""").toOption.get
    val d       = stui.db.DeliveryRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), None, "https://example.com/hook", "s3cret", "message.created", payload, 0)
    val req     = svc(allowPrivate = false).request(d)
    req.bodyText.compile.string.map { body =>
      assertEquals(body, payload.noSpaces)
      assertEquals(req.headers.get(org.typelevel.ci.CIString("X-Stui-Signature")).map(_.head.value),
        Some("sha256=" + stui.auth.Crypto.hmacHex("s3cret", body.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
      assertEquals(req.contentType.map(_.mediaType), Some(org.http4s.MediaType.application.json))
    }
  }
