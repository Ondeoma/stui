package stui.auth

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

final case class OidcConfig(
    issuer: String,
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    groupsClaim: String = "groups",
    scopes: List[String] = List("openid", "profile", "email")
)

final case class Identity(sub: String, email: Option[String], name: String, groups: List[String], picture: Option[String] = None, idToken: Option[String] = None)

final case class ProviderMetadata(issuer: String, authorizationEndpoint: Uri, tokenEndpoint: Uri, endSessionEndpoint: Option[Uri] = None)

object IdToken:

  /** JWTのペイロードを取り出す（署名は検証しない）。
    * IDトークンはトークンエンドポイントからTLS経由で直接受け取るため、OIDC Core 3.1.3.7 に従い
    * TLSサーバー認証でIssuerを検証できる場合は署名検証を省略できる。
    * 代わりに iss / aud / exp / nonce は必ず検証する。
    */
  def decodeClaims(jwt: String): Either[String, Json] =
    jwt.split('.') match
      case Array(_, payload, _*) =>
        Either
          .catchNonFatal(new String(Base64.getUrlDecoder.decode(payload), StandardCharsets.UTF_8))
          .leftMap(_ => "invalid base64 in id_token")
          .flatMap(parse(_).leftMap(e => s"invalid json in id_token: ${e.getMessage}"))
      case _ => Left("malformed id_token")

  def validate(
      claims: Json,
      issuer: String,
      cfg: OidcConfig,
      expectedNonce: String,
      now: Instant
  ): Either[String, Identity] =
    val c = claims.hcursor
    for
      iss <- c.get[String]("iss").leftMap(_ => "missing iss")
      _   <- Either.cond(iss == issuer, (), s"issuer mismatch: $iss")
      aud <- c.downField("aud").focus.toRight("missing aud").flatMap { j =>
        j.asString.map(List(_)).orElse(j.asArray.map(_.toList.flatMap(_.asString))).toRight("invalid aud")
      }
      _   <- Either.cond(aud.contains(cfg.clientId), (), "audience mismatch")
      exp <- c.get[Long]("exp").leftMap(_ => "missing exp")
      _   <- Either.cond(Instant.ofEpochSecond(exp).plusSeconds(60).isAfter(now), (), "id_token expired")
      non <- c.get[String]("nonce").leftMap(_ => "missing nonce")
      _   <- Either.cond(non == expectedNonce, (), "nonce mismatch")
      sub <- c.get[String]("sub").leftMap(_ => "missing sub")
    yield
      val email = c.get[String]("email").toOption
      val name = c.get[String]("name").toOption.filter(_.nonEmpty)
        .orElse(c.get[String]("preferred_username").toOption)
        .orElse(email)
        .getOrElse(sub)
      val groups = c.downField(cfg.groupsClaim).as[List[String]].getOrElse(Nil)
      Identity(sub, email, name, groups, c.get[String]("picture").toOption)

/** OIDCプロバイダ向けクライアント（Authorization Code + PKCE）。 */
final class OidcClient private (cfg: OidcConfig, http: Client[IO], meta: Ref[IO, Option[ProviderMetadata]]):

  private def discover: IO[ProviderMetadata] = meta.get.flatMap {
    case Some(m) => IO.pure(m)
    case None =>
      val url = Uri.unsafeFromString(cfg.issuer.stripSuffix("/") + "/.well-known/openid-configuration")
      http.expect[Json](url).flatMap { j =>
        val c = j.hcursor
        val res = for
          iss  <- c.get[String]("issuer")
          auth <- c.get[String]("authorization_endpoint")
          tok  <- c.get[String]("token_endpoint")
          a    <- Uri.fromString(auth).leftMap(e => io.circe.DecodingFailure(e.message, Nil))
          t    <- Uri.fromString(tok).leftMap(e => io.circe.DecodingFailure(e.message, Nil))
        yield ProviderMetadata(iss, a, t,
          c.get[String]("end_session_endpoint").toOption.flatMap(Uri.fromString(_).toOption))
        IO.fromEither(res).flatTap(m => meta.set(Some(m)))
      }
  }

  def authorizationUri(state: String, verifier: String, nonce: String): IO[Uri] =
    discover.map { m =>
      m.authorizationEndpoint.withQueryParams(
        Map(
          "response_type"         -> "code",
          "client_id"             -> cfg.clientId,
          "redirect_uri"          -> cfg.redirectUri,
          "scope"                 -> cfg.scopes.mkString(" "),
          "state"                 -> state,
          "nonce"                 -> nonce,
          "code_challenge"        -> Crypto.pkceChallenge(verifier),
          "code_challenge_method" -> "S256"
        )
      )
    }

  /** 認可コードをIDトークンに交換し、検証済みの Identity を返す。 */
  def authenticate(code: String, verifier: String, nonce: String, now: Instant): IO[Identity] =
    for
      m <- discover
      req = Request[IO](Method.POST, m.tokenEndpoint).withEntity(
        UrlForm(
          "grant_type"    -> "authorization_code",
          "code"          -> code,
          "redirect_uri"  -> cfg.redirectUri,
          "client_id"     -> cfg.clientId,
          "client_secret" -> cfg.clientSecret,
          "code_verifier" -> verifier
        )
      )
      resp    <- http.expect[Json](req)
      idToken <- IO.fromEither(resp.hcursor.get[String]("id_token").leftMap(e => new RuntimeException(s"no id_token: $e")))
      claims  <- IO.fromEither(IdToken.decodeClaims(idToken).leftMap(new RuntimeException(_)))
      id      <- IO.fromEither(IdToken.validate(claims, m.issuer, cfg, nonce, now).leftMap(new RuntimeException(_)))
    yield id.copy(idToken = Some(idToken))

  /** IdP からもログアウトする URL（end_session_endpoint がなければ None）. */
  def logoutUri(idToken: Option[String], postLogout: String): IO[Option[Uri]] =
    discover.attempt.map(_.toOption.flatMap(_.endSessionEndpoint).map { e =>
      e.withQueryParams(Map("post_logout_redirect_uri" -> postLogout, "client_id" -> cfg.clientId) ++ idToken.map("id_token_hint" -> _))
    })

object OidcClient:
  def create(cfg: OidcConfig, http: Client[IO]): IO[OidcClient] =
    Ref.of[IO, Option[ProviderMetadata]](None).map(new OidcClient(cfg, http, _))
