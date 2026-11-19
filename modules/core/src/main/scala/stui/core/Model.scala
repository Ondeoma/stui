package stui.core

import java.util.UUID

/** UUID として読めれば Some（読めなければ None）。 */
def uuidOpt(
  s: String
): Option[UUID] = scala.util.Try(UUID.fromString(s.trim)).toOption

/** APIキーのスコープ。 */
enum Scope {
  case Read, Write, Admin
}

object Scope {
  def parse(
    s: String
  ): Option[Scope] = s.trim.toLowerCase match {
    case "read" => Some(Read)
    case "write" => Some(Write)
    case "admin" => Some(Admin)
    case _ => None
  }
  extension (
    s: Scope
  ) {
    def asString: String = s match {
      case Read => "read"
      case Write => "write"
      case Admin => "admin"
    }
  }
}

final case class User(
  id: UUID,
  sub: String,
  name: String,
  email: Option[String],
  avatarUrl: Option[String],
  isAdmin: Boolean,
  disabled: Boolean,
  timezone: String,
  isBot: Boolean = false)
final case class Group(
  id: UUID,
  name: String)

/**
 * エージェント専用アカウント（skakapo などの外部の AI エージェントが API キーで使う。ログインはできない）。
 * ボット（is_bot）の一種で、sub は bot:agent:<UUID>。メールは持たない（同じメールの IdP のユーザーに乗っ取られないため）。
 */
object AgentAccounts {
  val SubPrefix = "bot:agent:"

  /** SQL の like で使う形。 */
  val SubLike = SubPrefix + "%"
  def isAgent(
    sub: String
  ): Boolean = sub.startsWith(SubPrefix)
  def newSub(): String = SubPrefix + UUID.randomUUID()

  /** ログインで使えない sub（ボット・エージェント）。 */
  def reservedSub(
    sub: String
  ): Boolean = sub.startsWith("bot:")

  /** 名前の確認（前後の空白を除いて 1〜60 文字、制御文字なし）。正しければ整えた名前。 */
  def validName(
    name: String
  ): Either[String, String] = {
    val n = name.trim
    if n.isEmpty then Left("名前を入力してください")
    else if n.length > 60 then Left("名前は 60 文字以内にしてください")
    else if n.exists(_.isControl) then Left("名前に制御文字は使えません")
    else Right(n)
  }
}

/** 認証手段。 */
enum AuthVia {
  case Session

  /** APIキー。scopes はキーのスコープ、client は OAuth で発行したキーのクライアント（skea など）、mcp は MCP 経由の呼び出し。 */
  case ApiKey(
    id: UUID,
    scopes: Set[Scope],
    client: Option[String] = None,
    mcp: Boolean = false)

  /** サーバーの中の処理（定期メッセージ・リマインダー）。 */
  case System(
    kind: String)
}

/** 認証済みの利用者。isAdmin はシステム管理者（ADMIN_GROUPS の所属、または画面で管理者にしたユーザー）。 */
final case class AuthSession(
  user: User,
  groups: List[Group],
  isAdmin: Boolean,
  via: AuthVia = AuthVia.Session) {

  /** 更新系の操作ができるか（read スコープだけのキーは不可）。 */
  def canWrite: Boolean = via match {
    case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Write) || scopes.contains(Scope.Admin)
    case _ => true
  }

  /** システム管理の操作ができるか（APIキーは admin スコープが必要）。 */
  def canAdmin: Boolean = isAdmin &&
    (via match {
      case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Admin)
      case _ => true
    })

  def viaString: String = via match {
    case AuthVia.Session => "web"
    case AuthVia.ApiKey(_, _, _, true) => "mcp"
    case AuthVia.ApiKey(_, _, Some(_), _) => "app"
    case _: AuthVia.ApiKey => "api"
    case AuthVia.System(k) => k
  }

  def isSession: Boolean = via == AuthVia.Session

  /** OAuth で発行したキーのクライアント（連携サービスからの呼び出し）。 */
  def oauthClient: Option[String] = via match {
    case AuthVia.ApiKey(_, _, c, _) => c
    case _ => None
  }

  def scopeStrings: List[String] = via match {
    case AuthVia.ApiKey(_, s, _, _) => s.toList.map(_.asString).sorted
    case _ => List("read", "write") ++ Option.when(isAdmin)("admin")
  }
}

/** チャンネルの名前。前後の空白を除き、80文字まで。# は付けない。改行・制御文字は不可。 */
object ChannelName {
  def normalize(
    s: String
  ): Either[String, String] = {
    val t = s.trim.stripPrefix("#").trim
    if t.isEmpty then Left("チャンネルの名前を入力してください")
    else if t.length > 80 then Left("チャンネルの名前は80文字までにしてください")
    else if t.exists(c => c.isControl) then Left("チャンネルの名前に改行や制御文字は使えません")
    else Right(t)
  }
}

/** DM の参加者を表すキー（同じ顔ぶれの DM は1つにする）。 */
object DmKey {
  def of(
    users: Iterable[UUID]
  ): String = users.toList.distinct.map(_.toString).sorted.mkString(",")
}
