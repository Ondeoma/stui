package stui.api

import cats.effect.*
import stui.core.*
import stui.db.*
import stui.shared.*
import sbirds.oauth.OAuthClient
import sbirds.shared.AppError

import java.time.{LocalDate, ZoneId}
import java.util.UUID
import scala.concurrent.duration.*

final case class ApiConfig(adminGroups: Set[String],
                           cookieSecure: Boolean,
                           publicUrl: String,
                           sessionTtl: FiniteDuration = 14.days,
                           cookieName: String = "stui_session",
                           devLogin: Boolean = false,
                           maxUploadBytes: Long = 100L * 1024 * 1024,
                           timezone: String = "Asia/Tokyo",
                           webhookAllowHttp: Boolean = false,
                           webhookAllowPrivate: Boolean = false,
                           version: String = "0.1.0",
                           oauthClients: List[OAuthClient] = Nil,
                           linkServices: List[LinkService] = Nil,
                           rateLimit: sbirds.http.RateLimitConfig = sbirds.http.RateLimitConfig(),
                           ice: sbirds.http.IceConfig = sbirds.http.IceConfig()) {
  def service(provider: String): Option[LinkService] = linkServices.find(_.provider == provider)
}

/**
 * 連携する兄弟のサービス（skiwi・skawau・skea・skorimako はリンクの名前、skaka は会議室の作成）。相手に stui をクライアントとして登録しておく。
 * url は画面から開く URL、internalUrl はサーバーから呼ぶ URL（省略時は url）。
 */
final case class LinkService(provider: String,
                             url: String,
                             clientId: String,
                             clientSecret: String,
                             internalUrl: Option[String] = None) {
  def apiUrl: String = internalUrl.getOrElse(url)
}

type Res[A] = IO[Either[AppError, A]]

/** 操作する人と、自動化の連鎖の深さ（ルールが別のルールを起動する深さ）。 */
final case class Op(s: AuthSession, depth: Int = 0) {
  def userId: UUID = s.user.id
  def via: String = s.viaString
  def today: LocalDate = LocalDate.now(Op.zone(s.user.timezone))
}

object Op {
  def zone(tz: String): ZoneId = scala.util.Try(ZoneId.of(tz)).getOrElse(ZoneId.of("Asia/Tokyo"))
}

/**
 * *Routes に共通の補助（エラー応答・認証・リクエスト本文・権限の確認）。
 */
/** 受け取ったファイル（一時ファイル）。 */
final case class Upload(name: String, contentType: String, path: fs2.io.file.Path, size: Long)

/** チャンネルで必要な権限。Read は閲覧（公開チャンネルは誰でも）、Post は投稿（メンバー・アーカイブ済みは不可）、Manage は管理。 */
enum Need {
  case Read, Member, Post, Manage
}

/** 権限を確かめたチャンネルと、自分のメンバーの情報。 */
final case class ChannelCtx(channel: ChannelRow, member: Option[MemberRow]) {
  def id: UUID = channel.id
  def isMember: Boolean = member.isDefined
  def isChannelAdmin: Boolean = member.exists(_.role == ChannelRole.Admin)
}

/** 日付の入力（yyyy-MM-dd）。 */
object Dates {
  def parse(s: Option[String], what: String): Either[AppError, Option[LocalDate]] =
    s.map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(v) =>
        scala.util.Try(LocalDate.parse(v)).toOption.toRight(AppError(
          ErrorCode.DateFormatInvalid,
          s"$what は yyyy-MM-dd で指定してください"
        )).map(Some(_))
    }
}
