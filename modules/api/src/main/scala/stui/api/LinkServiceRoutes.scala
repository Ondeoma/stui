package stui.api

import sbirds.links.IntegrationTokenStore
import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.{Authorization, Location}
import sbirds.auth.Crypto
import stui.auth.SecretBox
import stui.core.*
import stui.shared.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * 兄弟のサービスとの連携（docs/integrations.md）。
 * 各ユーザーが OAuth 2.0（認可コード + PKCE）で相手のサービスを許可し、stui は本人の権限で、貼られたリンク先の名前
 * （skiwi のページのタイトル・skawau のファイル名・skea のタスクのタイトル・skorimako のトレースやアラートの名前と状態）を読み、skaka に会議室を作る。
 * トークンは APP_SECRET の鍵で暗号化して保存する。
 */
final class LinkServiceRoutes(
  store: IntegrationTokenStore,
  http: Client[IO],
  box: SecretBox,
  cache: LinkServiceRoutes.Cache,
  chat: ChatService,
  val sessions: SessionAuth,
  val access: Access2,
  cfg: ApiConfig)
  extends RouteSupport {
  import LinkServiceRoutes.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /** 設定されたサービスと、自分が連携済みかどうか。 */
    case req @ GET -> Root / "api" / "integrations" / "links" => getIntegrationsLinks(req)
    case req @ GET -> Root / "api" / "integrations" / p / "connect" if kinds.contains(p) =>
      getIntegrationConnect(req, p)
    case req @ GET -> Root / "api" / "integrations" / p / "callback" if kinds.contains(p) =>
      getIntegrationCallback(req, p)
    case req @ DELETE -> Root / "api" / "integrations" / p / "connection" if kinds.contains(p) =>
      deleteIntegrationConnection(req, p)
    case req @ POST -> Root / "api" / "integrations" / p / "titles" if kinds.get(p).exists(_.lookupPath.nonEmpty) =>
      postIntegrationTitles(req, p)
    case req @ POST -> Root / "api" / "channels" / UuidVar(id) / "meeting" => postChannelMeeting(req, id)
  }

  private def getIntegrationsLinks(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      sessions.appNames(req).flatMap(names =>
        cfg.linkServices.traverse(c =>
          store.token(s.user.id, c.provider).map(t =>
            // 名前は sruru に登録した表示名（なければサービスの名前）
            LinkServiceDto(c.provider, names.getOrElse(c.provider, kinds(c.provider).name), c.url, t.isDefined)
          )
        )
      ).flatMap(Ok(_))
    )

  private def getIntegrationConnect(
    req: Request[IO],
    p: String
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(p) { c =>
        val state = Crypto.randomToken(24)
        val verifier = Crypto.randomToken(48)
        store.saveState(
          state,
          s.user.id,
          p,
          verifier,
          param(req, "returnTo").getOrElse("/settings"),
          Instant.now().plusSeconds(600)
        ) *>
          redirect(Uri.unsafeFromString(s"${c.url}/oauth/authorize").withQueryParams(Map(
            "response_type" -> "code",
            "client_id" -> c.clientId,
            "redirect_uri" -> callback(p),
            "scope" -> kinds(p).scope,
            "state" -> state,
            "code_challenge" -> Crypto.pkceChallenge(verifier),
            "code_challenge_method" -> "S256"
          )).renderString)
      }
    )

  private def getIntegrationCallback(
    req: Request[IO],
    p: String
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(p) { c =>
        param(req, "state").fold(IO.pure(None))(st => store.takeState(st, p)).flatMap {
          case Some((uid, _, back)) if uid != s.user.id => done(p, back, "error")
          case None => done(p, "/settings", "error")
          case Some((_, _, back)) if param(req, "error").isDefined =>
            done(p, back, if param(req, "error").contains("access_denied") then "denied" else "error")
          case Some((_, verifier, back)) =>
            param(req, "code") match {
              case None => done(p, back, "error")
              case Some(code) =>
                val form = UrlForm(
                  "grant_type" -> "authorization_code",
                  "code" -> code,
                  "redirect_uri" -> callback(p),
                  "code_verifier" -> verifier
                )
                http.run(Request[IO](
                  Method.POST,
                  Uri.unsafeFromString(s"${c.apiUrl}/oauth/token")
                ).withEntity(form).putHeaders(basic(c)))
                  .use(r =>
                    if r.status.isSuccess then r.as[Json].map(_.hcursor.get[String]("access_token").toOption)
                    else IO.pure(None)
                  )
                  .timeout(15.seconds).handleError(_ => None).flatMap {
                    case Some(token) => store.saveToken(s.user.id, p, box.encrypt(token)) *> forget(s.user.id, p) *>
                        done(p, back, "connected")
                    case None => done(p, back, "error")
                  }
            }
        }
      }
    )

  private def deleteIntegrationConnection(
    req: Request[IO],
    p: String
  ): IO[Response[IO]] =
    sessionOnly(req)(s =>
      withService(p) { c =>
        tokenOf(s.user.id, p).flatMap(_.traverse_ { t =>
          http.run(Request[IO](
            Method.POST,
            Uri.unsafeFromString(s"${c.apiUrl}/oauth/revoke")
          ).withEntity(UrlForm("token" -> t)).putHeaders(basic(c)))
            .use(_ => IO.unit).timeout(10.seconds).handleError(_ => ())
        }) *> store.deleteToken(s.user.id, p) *> forget(s.user.id, p) *> NoContent()
      }
    )

  private def postIntegrationTitles(
    req: Request[IO],
    p: String
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[LinkTitlesReq](req) { r =>
        val ids = r.ids.map(_.trim).filter(kinds(p).validId).distinct
        if ids.size > 100 then err(ErrorCode.TooManyIds)
        else
          service(p) match {
            case None => Ok(LinkTitlesDto(Nil, connected = false))
            case Some(c) =>
              tokenOf(s.user.id, p).flatMap {
                case None => Ok(LinkTitlesDto(Nil, connected = false))
                case Some(t) =>
                  lookup(c, s.user.id, t, ids).attempt.flatMap {
                    case Right(Some(ts)) => Ok(LinkTitlesDto(ts, connected = true))
                    // 相手でトークンが失効していたら連携を外す
                    case Right(None) => store.deleteToken(s.user.id, p) *> Ok(LinkTitlesDto(Nil, connected = false))
                    case Left(e) =>
                      err(ErrorCode.LinkServiceUnreachable, s"$p に問い合わせできませんでした: ${e.getMessage.take(200)}")
                  }
              }
          }
      }
    )

  private def postChannelMeeting(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withChannel(req, id, Need.Post)(
      (
        s,
        ctx
      ) =>
        body[MeetingReq](req) { r =>
          service("skaka") match {
            case None => err(ErrorCode.MeetingServiceNotConfigured)
            case Some(c) =>
              tokenOf(s.user.id, "skaka").flatMap {
                case None => err(ErrorCode.MeetingServiceNotLinked)
                case Some(token) =>
                  val title = r.name.map(_.trim).filter(_.nonEmpty).getOrElse(
                    if ctx.channel.isDm then s"${s.user.name} さんとの会議" else s"#${ctx.channel.name} の会議"
                  ).take(100)
                  val body = Json.obj(
                    "name" -> title.asJson,
                    "description" -> s"stui から作成".asJson,
                    "guestAccess" -> false.asJson,
                    "lobby" -> false.asJson
                  )
                  http.run(Request[IO](Method.POST, Uri.unsafeFromString(s"${c.apiUrl}/api/rooms")).withEntity(body)
                    .putHeaders(
                      Authorization(Credentials.Token(AuthScheme.Bearer, token)),
                      "Accept" -> "application/json"
                    ))
                    .use(res =>
                      if res.status.isSuccess then res.as[Json].map(Right(_)) else IO.pure(Left(res.status.code))
                    )
                    .timeout(15.seconds).attempt.flatMap {
                      case Right(Left(401)) => store.deleteToken(s.user.id, "skaka") *>
                          err(ErrorCode.MeetingServiceLinkExpired)
                      case Right(Left(code)) => err(ErrorCode.MeetingServiceError, s"skaka が $code を返しました")
                      case Left(e) =>
                        err(ErrorCode.MeetingServiceUnreachable, s"skaka に接続できませんでした: ${e.getMessage.take(200)}")
                      case Right(Right(room)) =>
                        val url = room.hcursor.get[String]("url").toOption.filter(u =>
                          u.startsWith("https://") || u.startsWith("http://")
                        ).getOrElse(c.url)
                        val name = room.hcursor.get[String]("name").getOrElse(title)
                        val code = room.hcursor.get[String]("code").toOption
                        val doc = RichText.fromMarkdown(s"📹 Web 会議を始めました: [$name]($url)")
                        val props = Json.obj("meeting" ->
                          Json.obj("url" -> url.asJson, "name" -> name.asJson, "code" -> code.asJson))
                        respond(chat.publish(
                          ctx.channel,
                          Author(s.user, s.viaString),
                          doc,
                          r.rootId,
                          false,
                          props,
                          "user"
                        ))(Created(_))
                    }
              }
          }
        }
    )

  private val CacheMillis = 60.seconds.toMillis

  private def callback(
    p: String
  ): String = s"${cfg.publicUrl}/api/integrations/$p/callback"

  private def basic(
    c: LinkService
  ) = Authorization(BasicCredentials(c.clientId, c.clientSecret))

  private def redirect(
    to: String
  ): IO[Response[IO]] = IO.pure(Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to))))

  /** 個人設定へ戻る（結果を integration=…&result=… に付ける）。 */
  private def done(
    p: String,
    returnTo: String,
    result: String
  ): IO[Response[IO]] = {
    // 同じサイトのパスだけ。開発中（DEV_LOGIN）は画面の開発サーバー（http://localhost:…）にも戻せる
    val devOrigin = cfg.devLogin &&
      (returnTo.startsWith("http://localhost:") || returnTo.startsWith("http://127.0.0.1:"))
    val back = Option(returnTo).filter(p => localPath(p) || devOrigin).getOrElse("/settings")
    redirect(back + (if back.contains("?") then "&" else "?") + s"integration=$p&result=$result")
  }

  private def service(
    p: String
  ): Option[LinkService] = cfg.linkServices.find(_.provider == p)

  private def withService(
    p: String
  )(
    f: LinkService => IO[Response[IO]]
  ): IO[Response[IO]] =
    service(p).fold(err(ErrorCode.LinkServiceNotConfigured, s"$p との連携は設定されていません"))(f)

  private def tokenOf(
    uid: UUID,
    p: String
  ): IO[Option[String]] = store.token(uid, p).map(_.flatMap(box.decrypt))

  private def forget(
    uid: UUID,
    p: String
  ): IO[Unit] = cache.update(_.filterNot(k => k._1._1 == p && k._1._2 == uid))

  /** 本人のトークンでリンク先の名前をまとめて読む（60秒キャッシュ。見られないものも「なし」として覚える）。トークンが失効していたら None。 */
  private def lookup(
    c: LinkService,
    uid: UUID,
    token: String,
    ids: List[String]
  ): IO[Option[List[LinkTitleDto]]] = {
    val kind = kinds(c.provider)
    for {
      now <- IO.realTime.map(_.toMillis)
      known <-
        cache.get.map(m => ids.flatMap(id => m.get((c.provider, uid, id)).filter(_._1 > now).map(id -> _._2)).toMap)
      miss = ids.filterNot(known.contains)
      fetched <- if miss.isEmpty then IO.pure(Some(Nil))
      else {
        val req = Request[IO](Method.POST, Uri.unsafeFromString(c.apiUrl + kind.lookupPath))
          .withEntity(Json.obj(kind.requestKey -> miss.asJson))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)), "Accept" -> "application/json")
        http.run(req).use { r =>
          if r.status == Status.Unauthorized then IO.pure(None)
          else if !r.status.isSuccess then
            IO.raiseError(new RuntimeException(s"${c.provider} が ${r.status.code} を返しました"))
          else
            r.as[Json].map(j =>
              Some(j.hcursor.downField(kind.listKey).as[List[Json]].getOrElse(Nil).flatMap(kind.parse))
            )
        }.timeout(10.seconds)
      }
      res <- fetched match {
        case None => IO.pure(None)
        case Some(ts) =>
          cache.update(m =>
            (m ++ miss.map(id => (c.provider, uid, id) -> (now + CacheMillis, ts.find(_.id == id)))).filter(_._2._1 >
              now)
          ).as(
            Some(ids.flatMap(id => known.get(id).flatten.orElse(ts.find(_.id == id))))
          )
      }
    } yield res
  }

}

object LinkServiceRoutes {

  /** (サービス, ユーザー, ID) → (期限, 名前) */
  type Cache = Ref[IO, Map[(String, UUID, String), (Long, Option[LinkTitleDto])]]

  def newCache: IO[Cache] = Ref.of(Map.empty)

  /**
   * サービスごとの違い: 表示名、許可を求めるスコープ、問い合わせの API（なければ名前は出さない）、送る配列の名前、
   * 応答の配列の名前、1件の読み方、ID の形。
   */
  final case class Kind(
    name: String,
    scope: String,
    lookupPath: String,
    requestKey: String,
    listKey: String,
    parse: Json => Option[LinkTitleDto],
    validId: String => Boolean)

  private def field[A: Decoder](
    j: Json,
    k: String
  ): Option[A] = j.hcursor.get[A](k).toOption

  private def isUuid(
    s: String
  ): Boolean = scala.util.Try(UUID.fromString(s)).isSuccess

  /** skiwi: {"pages":[{"id","workspaceId","title","icon"}]} */
  private def skiwiPage(
    j: Json
  ): Option[LinkTitleDto] =
    (field[UUID](j, "id"), field[String](j, "title")).mapN(
      (
        id,
        t
      ) => LinkTitleDto(id.toString, t, field[Option[String]](j, "icon").flatten.orElse(Some("📄")))
    )

  /** skea: {"tasks":[{"key","title","status":{"name","category"}}]} */
  private def skeaTask(
    j: Json
  ): Option[LinkTitleDto] =
    (field[String](j, "key"), field[String](j, "title")).mapN {
      (
        k,
        t
      ) =>
        val done = j.hcursor.downField("status").get[String]("category").toOption.contains("done")
        LinkTitleDto(k, s"$k $t", Some(if done then "✅" else "📋"))
    }

  /** skorimako: {"links":[{"id","kind","title","summary","status","icon"}]}（id は traces/…・alerts/…・dashboards/…） */
  private def skorimakoLink(
    j: Json
  ): Option[LinkTitleDto] =
    (field[String](j, "id"), field[String](j, "title")).mapN {
      (
        id,
        t
      ) =>
        val summary = field[String](j, "summary").filter(_.nonEmpty)
        LinkTitleDto(id, summary.fold(t)(x => s"$t（$x）"), field[String](j, "icon").orElse(Some("📈")))
    }

  private val skorimakoId =
    "^(traces/[0-9a-f]{1,64}|(alerts|dashboards)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$".r

  /** skawau: {"files":[{"id","name","folder"}]} */
  private def skawauFile(
    j: Json
  ): Option[LinkTitleDto] =
    (field[UUID](j, "id"), field[String](j, "name")).mapN(
      (
        id,
        n
      ) => LinkTitleDto(id.toString, n, Some(FileIcons.of(n, field[Boolean](j, "folder").contains(true))))
    )

  val kinds: Map[String, Kind] = Map(
    "skiwi" -> Kind("skiwi", "read", "/api/pages/lookup", "ids", "pages", skiwiPage, isUuid),
    "skawau" -> Kind("skawau", "read", "/api/files/lookup", "ids", "files", skawauFile, isUuid),
    "skea" -> Kind(
      "skea",
      "read",
      "/api/tasks/lookup",
      "keys",
      "tasks",
      skeaTask,
      _.matches("^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,9}$")
    ),
    // skorimako（オブザーバビリティ）: トレース・アラート・ダッシュボードの名前と状態
    "skorimako" -> Kind("skorimako", "read", "/api/links/titles", "ids", "links", skorimakoLink, skorimakoId.matches),
    // skaka は会議室を作るだけ（リンクの名前は出さない）
    "skaka" -> Kind("skaka（Web 会議）", "read write", "", "ids", "rooms", _ => None, _ => false)
  )

  /** 環境変数を読む順。 */
  val providers: List[String] = List("skiwi", "skawau", "skea", "skaka", "skorimako")
}
