package stui.api

import cats.effect.*
import stui.core.*
import stui.db.*
import sbirds.shared.AppError

import java.util.UUID

/** 権限の確認（ルートとサービスで共通）。見えないものは 404、権限が足りなければ 403。 */
final class Access2(channels: ChannelStore, messages: MessageStore) {

  def channel(s: AuthSession, id: UUID, need: Need): Res[ChannelCtx] =
    channels.access(id, s.user.id).map {
      case None => Left(AppError(ErrorCode.ChannelNotFound))
      case Some((c, m)) =>
        val ctx = ChannelCtx(c, m)
        // 非公開チャンネル・DM はメンバーにしか存在を見せない
        if !c.isPublic && m.isEmpty then Left(AppError(ErrorCode.ChannelNotFound))
        else
          need match {
            case Need.Read => Right(ctx)
            case Need.Member => if m.isDefined then Right(ctx) else Left(AppError(ErrorCode.ChannelJoinRequired))
            case Need.Post =>
              if c.archived then Left(AppError(ErrorCode.ChannelArchivedPost))
              else if m.isEmpty then Left(AppError(ErrorCode.ChannelJoinRequired, "チャンネルに参加してから投稿してください"))
              else if !s.canWrite then Left(AppError(ErrorCode.ApiKeyReadOnly))
              else Right(ctx)
            case Need.Manage =>
              if c.isDm then Left(AppError(ErrorCode.DmSettingsReadOnly))
              else if ctx.isChannelAdmin || s.canAdmin then Right(ctx)
              else Left(AppError(ErrorCode.ChannelAdminOnly))
          }
    }

  def message(s: AuthSession, id: UUID, need: Need): Res[(ChannelCtx, MessageRow)] =
    messages.find(id).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.MessageNotFound)))
      case Some(m) => channel(s, m.channelId, need).map {
          case Left(e) if e.status == 404 => Left(AppError(ErrorCode.MessageNotFound))
          case other => other.map(_ -> m)
        }
    }
}
