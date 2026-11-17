package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import stui.core.*
import stui.shared.*

import java.time.Instant
import java.util.UUID

/** ユーザー・グループ・セッション・ログイン途中の状態・APIキー。 */
final class UserStore(xa: Transactor[IO]) extends TxStore(xa):

  private val userCols = fr"u.id, u.sub, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.timezone, u.is_bot"

  // ---------------------------------------------------------------- ユーザー

  /** ログイン時に作成・更新する。timezone は新しく作るときだけ使う（既定のタイムゾーン。あとは本人が個人設定で変える）。 */
  def upsert(sub: String, name: String, email: Option[String], avatar: Option[String], timezone: String = "Asia/Tokyo"): IO[User] = tx(
    sql"""insert into app_user (id, sub, name, email, avatar_url, timezone, last_login_at)
          values (${UUID.randomUUID()}, $sub, $name, $email, $avatar, $timezone, now())
          on conflict (sub) do update set name = excluded.name, email = excluded.email,
            avatar_url = case when app_user.avatar_key is not null then app_user.avatar_url else coalesce(excluded.avatar_url, app_user.avatar_url) end,
            last_login_at = now()
          returning id, sub, name, email, avatar_url, is_admin, disabled, timezone, is_bot""".query[User].unique
  )

  /** ボット（Webhook・連携サービス・リマインダーの発言者）。sub は bot:<名前>。なければ作る。 */
  def bot(sub: String, name: String, avatar: Option[String] = None): IO[User] = tx(
    sql"""insert into app_user (id, sub, name, avatar_url, is_bot) values (${UUID.randomUUID()}, $sub, $name, $avatar, true)
          on conflict (sub) do update set name = excluded.name, avatar_url = coalesce(excluded.avatar_url, app_user.avatar_url)
          returning id, sub, name, email, avatar_url, is_admin, disabled, timezone, is_bot""".query[User].unique
  )

  def find(id: UUID): IO[Option[User]] = tx(findC(id))

  def findC(id: UUID): ConnectionIO[Option[User]] =
    (fr"select" ++ userCols ++ fr"from app_user u where u.id = $id").query[User].option

  /** ログイン時に IdP の groups クレームで所属を置き換える。 */
  def syncGroups(userId: UUID, names: List[String]): IO[Unit] = tx {
    val ns = names.map(_.trim).filter(_.nonEmpty).distinct
    for
      ids <- ns.traverse(n =>
        sql"""insert into user_group (id, name) values (${UUID.randomUUID()}, $n)
              on conflict (name) do update set name = excluded.name returning id""".query[UUID].unique)
      _ <- sql"delete from group_member where user_id = $userId".update.run
      _ <- ids.traverse_(g => sql"insert into group_member (group_id, user_id) values ($g, $userId) on conflict do nothing".update.run)
    yield ()
  }

  def groupsOf(userId: UUID): ConnectionIO[List[Group]] =
    sql"select g.id, g.name from user_group g join group_member m on m.group_id = g.id where m.user_id = $userId order by g.name"
      .query[Group].to[List]

  def withGroups(userId: UUID): IO[Option[(User, List[Group])]] = tx(
    findC(userId).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
  )

  def groupByName(name: String): IO[UUID] = tx(
    sql"""insert into user_group (id, name) values (${UUID.randomUUID()}, $name)
          on conflict (name) do update set name = excluded.name returning id""".query[UUID].unique
  )

  /** 一覧（管理画面・メンションの候補）。q で名前を絞る。 */
  def list(q: Option[String], includeDisabled: Boolean, limit: Int = 500, includeBots: Boolean = false): IO[List[UserDto]] = tx {
    val where = List(
      Option.when(!includeBots)(fr"not u.is_bot"),
      q.filter(_.trim.nonEmpty).map(s => fr"(u.name &@~ ${SearchQuery.phrase(s.trim)} or u.name ilike ${"%" + s.trim + "%"} or u.email ilike ${"%" + s.trim + "%"})"),
      Option.when(!includeDisabled)(fr"not u.disabled")
    ).flatten
    (fr"""select u.id, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.is_bot, u.last_login_at,
                 coalesce((select array_agg(g.name order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}')
          from app_user u""" ++ Sql.whereAnd(where) ++ fr"order by u.name limit $limit")
      .query[(UUID, String, Option[String], Option[String], Boolean, Boolean, Boolean, Option[Instant], List[String])]
      .map((id, n, e, a, ad, d, b, l, g) => UserDto(id, n, e, a, ad, d, b, l, g)).to[List]
  }

  def update(id: UUID, isAdmin: Option[Boolean], disabled: Option[Boolean]): IO[Boolean] = tx {
    val sets = List(isAdmin.map(v => fr"is_admin = $v"), disabled.map(v => fr"disabled = $v")).flatten
    if sets.isEmpty then findC(id).map(_.isDefined)
    else
      for
        n <- (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run
        _ <- if disabled.contains(true) then sql"delete from session where user_id = $id".update.run else 0.pure[ConnectionIO]
      yield n > 0
  }

  /** 画面から登録したアイコン（S3 のキー）. */
  def avatarKey(id: UUID): IO[Option[String]] = tx(sql"select avatar_key from app_user where id = $id".query[Option[String]].option.map(_.flatten))

  /** アイコンを登録する（key が None なら外す）. 前のキーを返す. */
  def setAvatar(id: UUID, key: Option[String], url: Option[String]): IO[Option[String]] = tx(
    for
      old <- sql"select avatar_key from app_user where id = $id".query[Option[String]].option.map(_.flatten)
      _   <- sql"update app_user set avatar_key = $key, avatar_url = $url where id = $id".update.run
    yield old
  )

  def updateMe(id: UUID, name: Option[String], timezone: Option[String]): IO[Unit] = tx {
    val sets = List(name.map(_.trim).filter(_.nonEmpty).map(v => fr"name = $v"), timezone.map(v => fr"timezone = $v")).flatten
    if sets.isEmpty then ().pure[ConnectionIO] else (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run.void
  }

  /** 何人かのユーザーの短い情報（無効なユーザーも含む）。 */
  def briefs(ids: List[UUID]): ConnectionIO[List[UserBrief]] =
    sql"select id, name, avatar_url, is_bot from app_user where id = any(${ids.toArray})".query[UserBrief].to[List]

  def briefsIO(ids: List[UUID]): IO[List[UserBrief]] = tx(briefs(ids))

  def groupMembers(groupId: UUID): IO[List[UUID]] = tx(groupMemberIds(groupId))

  /** グループの人（有効なユーザーだけ）。 */
  def groupMemberIds(groupId: UUID): ConnectionIO[List[UUID]] =
    sql"select m.user_id from group_member m join app_user u on u.id = m.user_id where m.group_id = $groupId and not u.disabled".query[UUID].to[List]

  def groups: IO[List[(UUID, String, Int)]] = tx(
    sql"""select g.id, g.name, count(m.user_id)::int from user_group g left join group_member m on m.group_id = g.id
          group by g.id, g.name order by g.name""".query[(UUID, String, Int)].to[List]
  )

  /** 名前・メール・ID でユーザーを探す（API の指定用）。 */
  def resolve(s: String): IO[Option[UUID]] = tx {
    val t = s.trim
    uuidOpt(t) match
      case Some(id) => sql"select id from app_user where id = $id".query[UUID].option
      case None => sql"select id from app_user where not disabled and (lower(email) = lower($t) or name = $t or sub = $t) order by last_login_at desc nulls last limit 1".query[UUID].option
  }

  // ---------------------------------------------------------------- 通知の設定

  def notifyPrefs(userId: UUID): IO[List[NotifyPrefDto]] = tx(
    sql"select kind, email from notify_pref where user_id = $userId".query[(String, Boolean)].to[List]
  ).map { rows =>
    val m = rows.toMap
    NotificationKind.all.map(k => NotifyPrefDto(k, m.getOrElse(k, true)))
  }

  def setNotifyPrefs(userId: UUID, prefs: List[NotifyPrefDto]): IO[Unit] = tx(
    prefs.filter(p => NotificationKind.all.contains(p.kind)).traverse_(p =>
      sql"""insert into notify_pref (user_id, kind, email) values ($userId, ${p.kind}, ${p.email})
            on conflict (user_id, kind) do update set email = excluded.email""".update.run)
  )

  // ---------------------------------------------------------------- セッション

  def createSession(hash: Array[Byte], userId: UUID, expires: Instant): IO[Unit] = tx(
    sql"insert into session (token_hash, user_id, expires_at) values ($hash, $userId, $expires)".update.run.void
  )

  def findSession(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group])]] = tx(
    sql"select user_id from session where token_hash = $hash and expires_at > $now".query[UUID].option.flatMap {
      case None => none[(User, List[Group])].pure[ConnectionIO]
      case Some(uid) => findC(uid).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
    }
  )

  def deleteSession(hash: Array[Byte]): IO[Unit] = tx(sql"delete from session where token_hash = $hash".update.run.void)

  def purgeExpired(now: Instant): IO[Unit] = tx(
    sql"delete from session where expires_at < $now".update.run *>
      sql"delete from login_state where created_at < now() - interval '1 hour'".update.run.void
  )

  // ---------------------------------------------------------------- ログイン途中の状態（OIDC）

  def saveLoginState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] = tx(
    sql"insert into login_state (state, verifier, nonce, return_to) values ($state, $verifier, $nonce, $returnTo)".update.run.void
  )

  /** 取り出して消す（1回だけ使える）。 */
  def takeLoginState(state: String): IO[Option[(String, String, String)]] = tx(
    sql"""delete from login_state where state = $state and created_at > now() - interval '15 minutes'
          returning verifier, nonce, return_to""".query[(String, String, String)].option
  )

  // ---------------------------------------------------------------- APIキー

  def createKey(userId: UUID, name: String, prefix: String, hash: Array[Byte], scopes: List[String], expires: Option[Instant]): IO[ApiKeyDto] = tx(
    sql"""insert into api_key (id, user_id, name, prefix, key_hash, scopes, expires_at)
          values (${UUID.randomUUID()}, $userId, $name, $prefix, $hash, ${scopes.toArray}, $expires)
          returning id, name, prefix, scopes, created_at, expires_at, last_used_at, oauth_client""".query[ApiKeyDto].unique
  )

  def keys(userId: UUID): IO[List[ApiKeyDto]] = tx(
    sql"""select id, name, prefix, scopes, created_at, expires_at, last_used_at, oauth_client from api_key
          where user_id = $userId and revoked_at is null order by created_at desc""".query[ApiKeyDto].to[List]
  )

  def revokeKey(userId: UUID, id: UUID): IO[Boolean] = tx(
    sql"update api_key set revoked_at = now() where id = $id and user_id = $userId and revoked_at is null".update.run.map(_ > 0)
  )

  /** 有効なキー。最終使用日時は1分単位で記録する。 */
  def findKey(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group], UUID, List[String], Option[String])]] = tx(
    sql"""select k.id, k.user_id, k.scopes, k.oauth_client from api_key k join app_user u on u.id = k.user_id
          where k.key_hash = $hash and k.revoked_at is null and (k.expires_at is null or k.expires_at > $now) and not u.disabled"""
      .query[(UUID, UUID, List[String], Option[String])].option.flatMap {
        case None => none.pure[ConnectionIO]
        case Some((kid, uid, scopes, client)) =>
          for
            _  <- sql"update api_key set last_used_at = $now where id = $kid and (last_used_at is null or last_used_at < $now - interval '1 minute')".update.run
            u  <- findC(uid)
            gs <- groupsOf(uid)
          yield u.map(x => (x, gs, kid, scopes, client))
      }
  )
