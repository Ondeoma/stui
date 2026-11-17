package stui.push

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.util.UUID
import scala.concurrent.duration.*

/** プッシュ通知の購読と VAPID の鍵（テーブル push_subscription・push_config. マイグレーションは db モジュール）. */
final class PushStore(xa: Transactor[IO]):

  private def tx[A](c: ConnectionIO[A]): IO[A] = c.transact(xa)

  /** VAPID の鍵. なければ作って保存する（複数ノードで同時に起動しても1つに決まる）. */
  def vapidKeys: IO[VapidKeys] =
    val fresh = WebPush.generateKeys()
    tx(
      sql"""insert into push_config (id, vapid_public, vapid_private) values (1, ${fresh.publicKey}, ${fresh.privateKey})
            on conflict (id) do nothing""".update.run *>
        sql"select vapid_public, vapid_private from push_config where id = 1".query[(String, String)].unique
    ).map(VapidKeys.apply.tupled)

  def subscribe(userId: UUID, s: Subscription, userAgent: Option[String]): IO[Unit] = tx(
    sql"""insert into push_subscription (id, user_id, endpoint, p256dh, auth, user_agent) values (${UUID.randomUUID()}, $userId, ${s.endpoint}, ${s.p256dh}, ${s.auth}, $userAgent)
          on conflict (endpoint) do update set user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth,
            user_agent = excluded.user_agent, created_at = now()""".update.run.void
  )

  def unsubscribe(userId: UUID, endpoint: String): IO[Unit] =
    tx(sql"delete from push_subscription where user_id = $userId and endpoint = $endpoint".update.run.void)

  def forUser(userId: UUID): IO[List[Subscription]] =
    tx(sql"select endpoint, p256dh, auth from push_subscription where user_id = $userId".query[Subscription].to[List])

  def count(userId: UUID): IO[Int] =
    tx(sql"select count(*) from push_subscription where user_id = $userId".query[Int].unique)

  /** プッシュのサービスが「もう無い」と答えた購読を消す. */
  def remove(endpoint: String): IO[Unit] = tx(sql"delete from push_subscription where endpoint = $endpoint".update.run.void)

  def touch(endpoint: String): IO[Unit] = tx(sql"update push_subscription set last_used_at = now() where endpoint = $endpoint".update.run.void)

/** 通知の中身（Service Worker の push で表示する）. url は開くページ（同じサイトのパス）、tag は同じものをまとめる印. */
final case class PushMessage(title: String, body: String, url: String, tag: Option[String] = None)

/**
 * プッシュ通知を送る. VAPID の subject は `VAPID_SUBJECT`（`mailto:` か `https:`. 省略すると公開 URL）.
 * 送れなかった購読（404・410）は消す. 失敗しても呼び出し元の処理は止めない.
 */
final class PushService(store: PushStore, http: Client[IO], keys: VapidKeys, subject: String, service: String):

  def publicKey: String = keys.publicKey

  def send(userId: UUID, m: PushMessage): IO[Unit] =
    stui.observe.Telemetry.span("push.send")(store.forUser(userId).flatMap(_.traverse_(s => deliver(s, m)))).handleErrorWith(e => stui.observe.Log.warn(s"push failed: $e"))

  private def deliver(s: Subscription, m: PushMessage): IO[Unit] =
    if !WebPush.allowedEndpoint(s.endpoint) then store.remove(s.endpoint)
    else
      val payload = Json.obj("title" -> Json.fromString(m.title.take(200)), "body" -> Json.fromString(m.body.take(1000)),
        "url" -> Json.fromString(m.url), "tag" -> m.tag.fold(Json.Null)(Json.fromString)).noSpaces
      for
        now  <- IO.realTime.map(_.toSeconds)
        body <- IO(WebPush.encrypt(s, payload.getBytes("UTF-8")))
        req = Request[IO](Method.POST, Uri.unsafeFromString(s.endpoint))
                .withEntity(body)
                .putHeaders(
                  Header.Raw(CIString("Authorization"), WebPush.vapidAuthorization(keys, s.endpoint, subject, now)),
                  Header.Raw(CIString("Content-Encoding"), "aes128gcm"),
                  Header.Raw(CIString("Content-Type"), "application/octet-stream"),
                  Header.Raw(CIString("TTL"), "86400"),
                  Header.Raw(CIString("Urgency"), "normal"))
        code <- http.run(req).use(r => IO.pure(r.status.code)).timeout(15.seconds).attempt
        _ <- code match
               case Right(c) if c >= 200 && c < 300 => store.touch(s.endpoint) *> stui.observe.Metrics.count(s"$service.push.sent", "result" -> "ok")
               case Right(c) if c == 404 || c == 410 => store.remove(s.endpoint) *> stui.observe.Metrics.count(s"$service.push.sent", "result" -> "gone")
               case Right(c) => stui.observe.Log.warn(s"push rejected: HTTP $c") *> stui.observe.Metrics.count(s"$service.push.sent", "result" -> "error")
               case Left(e)  => stui.observe.Log.warn(s"push failed: ${e.getClass.getSimpleName}") *> stui.observe.Metrics.count(s"$service.push.sent", "result" -> "error")
      yield ()

object PushService:

  def create(store: PushStore, http: Client[IO], publicUrl: String, service: String): IO[PushService] =
    store.vapidKeys.map { k =>
      val subject = sys.env.get("VAPID_SUBJECT").map(_.trim).filter(s => s.startsWith("mailto:") || s.startsWith("https://")).getOrElse(publicUrl)
      new PushService(store, http, k, subject, service)
    }
