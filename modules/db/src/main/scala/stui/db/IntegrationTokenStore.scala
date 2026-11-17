package stui.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** 外部のサービス（skiwi・skawau）のユーザーごとのトークンと、認可の途中の state。 */
final class IntegrationTokenStore(xa: Transactor[IO]) extends TxStore(xa):

  def saveState(state: String, userId: UUID, provider: String, verifier: String, returnTo: String, expires: Instant): IO[Unit] = tx(
    sql"""insert into integration_state (state, user_id, provider, code_verifier, return_to, expires_at)
          values ($state, $userId, $provider, $verifier, $returnTo, $expires)""".update.run *>
      sql"delete from integration_state where expires_at < now()".update.run.void
  )

  /** state を取り出す（一度だけ。期限切れなら None）。(ユーザー, code_verifier, 戻り先) */
  def takeState(state: String, provider: String): IO[Option[(UUID, String, String)]] = tx(
    sql"""delete from integration_state where state = $state and provider = $provider and expires_at > now()
          returning user_id, code_verifier, return_to""".query[(UUID, String, String)].option
  )

  def saveToken(userId: UUID, provider: String, tokenEnc: String): IO[Unit] = tx(
    sql"""insert into integration_token (user_id, provider, token_enc) values ($userId, $provider, $tokenEnc)
          on conflict (user_id, provider) do update set token_enc = excluded.token_enc, created_at = now()""".update.run.void
  )

  def token(userId: UUID, provider: String): IO[Option[String]] = tx(
    sql"select token_enc from integration_token where user_id = $userId and provider = $provider".query[String].option
  )

  def deleteToken(userId: UUID, provider: String): IO[Unit] = tx(
    sql"delete from integration_token where user_id = $userId and provider = $provider".update.run.void
  )
