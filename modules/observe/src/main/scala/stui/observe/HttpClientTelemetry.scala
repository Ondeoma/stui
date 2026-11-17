package stui.observe

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.trace.{SpanKind, StatusCode}

/**
 * 外への HTTP の呼び出しの観測（兄弟のサービス・Webhook・OIDC・プッシュのサービス）:
 *  - 子のスパン（例: `GET skiwi.example.com`）. メソッド・ホスト・状態コード
 *  - W3C の traceparent を付けて送る. 相手も OpenTelemetry に対応していれば、サービスをまたいで1つのトレースになる
 * 親のスパンがないときは何もしない. 本文（ストリーム）の読み終わりまでをスパンの時間にする.
 */
object HttpClientTelemetry:

  private given TextMapUpdater[Headers] with
    def updated(carrier: Headers, key: String, value: String): Headers = carrier.put(Header.Raw(CIString(key), value))

  def apply(client: Client[IO]): Client[IO] = Client[IO] { req =>
    val t = Telemetry.current
    Resource.eval(t.tracer.currentSpanContext).flatMap {
      case Some(parent) if parent.isValid =>
        val host = req.uri.host.map(_.value).getOrElse("")
        val attrs = List[Attribute[?]](
          Attribute("http.request.method", req.method.name), Attribute("server.address", host),
          Attribute("url.full", s"${req.uri.scheme.fold("https")(_.value.toString)}://$host${HttpTelemetry.masked(req.uri.path.renderString)}")
        ) ++ req.uri.port.map(p => Attribute("server.port", p.toLong)).toList
        for
          span <- Resource.make(t.tracer.spanBuilder(s"${req.method.name} $host").withSpanKind(SpanKind.Client).addAttributes(attrs).build.startUnmanaged)(_.end)
          hdrs <- Resource.eval(t.tracer.childScope(span.context)(t.tracer.propagate(req.headers)))
          res  <- client.run(req.withHeaders(hdrs)).attempt.evalTap {
                    case Right(r) => span.addAttributes(List(Attribute("http.response.status_code", r.status.code.toLong))) *>
                                     (if r.status.code >= 500 then span.setStatus(StatusCode.Error) else IO.unit)
                    case Left(e)  => span.recordException(e) *> span.setStatus(StatusCode.Error, e.getClass.getSimpleName)
                  }.rethrow
        yield res
      case _ => client.run(req)
    }
  }
