package stui.api

import cats.effect.*
import sbirds.auth.Crypto
import stui.auth.ApiKeys
import stui.core.*
import stui.db.*
import stui.shared.*
import sbirds.shared.AppError

import java.time.Instant
import java.util.UUID

/**
 * APIキーの発行（自分用のキーと、管理者が発行するエージェント専用アカウントのキーで共通）。
 * adminScope は admin スコープを付けられないときの失敗（付けてよければ None）。平文のキーは応答で1回だけ返す。
 */
object ApiKeyIssue {
  def apply(users: UserStore, userId: UUID, r: CreateApiKeyReq, adminScope: Option[AppError]): Res[CreatedApiKeyDto] = {
    val scopes = r.scopes.map(_.trim.toLowerCase).distinct
    if r.name.trim.isEmpty then IO.pure(Left(AppError(ErrorCode.NameRequired)))
    else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then
      IO.pure(Left(AppError(ErrorCode.ApiKeyScopeInvalid)))
    else
      adminScope.filter(_ => scopes.contains(ApiScope.Admin)) match {
        case Some(f) => IO.pure(Left(f))
        case None =>
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(userId, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).map(info =>
            Right(CreatedApiKeyDto(key, info))
          )
      }
  }
}
