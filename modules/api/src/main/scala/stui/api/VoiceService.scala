package stui.api

import sbirds.http.IceConfig
import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.util.UUID
import scala.concurrent.duration.*

/**
 * ボイスチャンネル. 音声・画面共有は参加者どうしが WebRTC で直接送り合う（メッシュ）. サーバーは顔ぶれ・状態と、
 * 接続の取り決め（SDP・ICE の候補）の受け渡しだけを受け持つ. 取り決めは DB を通すので、どのノードにつないでいても届く.
 */
final class VoiceService(voice: VoiceStore,
                         channels: ChannelStore,
                         chat: ChatService,
                         access: Access2,
                         ice: IceConfig) {

  /** 状態がこの間届かなければ外す（画面は15秒ごとに送る）. */
  private val Timeout = 45.seconds

  /** 取り決めの大きさの上限（SDP は数 KB）. */
  val MaxSignalBytes = 64 * 1024

  /** 自分が参加しているチャンネル（と、見ている公開チャンネル）のボイスチャンネルにいる人. */
  def rooms(s: AuthSession, watch: Option[UUID]): Res[List[VoiceRoomDto]] =
    watch.fold(IO.pure(Right(())): Res[Unit])(c => access.channel(s, c, Need.Read).map(_.void)).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) => voice.roomsFor(s.user.id, watch).map(Right(_))
    }

  /** 入る. 公開チャンネルに参加していなければ、先に参加する. */
  def join(s: AuthSession, r: VoiceJoinReq): Res[VoiceJoinDto] =
    access.channel(s, r.channelId, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if !ctx.channel.voice => IO.pure(Left(AppError(ErrorCode.VoiceNotEnabled)))
      case Right(ctx) if ctx.channel.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchived)))
      case Right(ctx) =>
        val joined = if ctx.isMember then IO.pure(Right(())) else chat.join(s, ctx.id).map(_.void)
        joined.flatMap {
          case Left(f) => IO.pure(Left(f))
          case Right(_) =>
            val conn = UUID.randomUUID()
            for {
              _ <- voice.join(conn, ctx.id, s.user.id, r.muted.getOrElse(false), r.deafened.getOrElse(false))
              now <- IO.realTimeInstant
              room <- voice.room(ctx.id)
            } yield Right(VoiceJoinDto(conn, ice.forPeer(s.user.id.toString, now), room))
        }
    }

  def leave(s: AuthSession, r: VoiceLeaveReq): IO[Unit] = voice.leave(r.connId, s.user.id).void

  /** 状態を送る（15秒ごとにも送る）. 外れていれば VoiceNotJoined. */
  def update(s: AuthSession, r: VoiceStateReq): Res[Unit] =
    voice.update(r.connId, s.user.id, r.muted, r.deafened, r.sharing).map(ok =>
      Either.cond(ok, (), AppError(ErrorCode.VoiceNotJoined))
    )

  /** 同じボイスチャンネルの相手に取り決めを送る. */
  def signal(s: AuthSession, r: VoiceSignalReq): Res[Unit] = {
    val data = r.data.noSpaces
    if data.length > MaxSignalBytes then IO.pure(Left(AppError(ErrorCode.VoiceSignalTooLarge)))
    else
      voice.conn(r.connId).flatMap {
        case Some(c) if c.userId == s.user.id =>
          voice.signal(r.connId, r.to, data).map(ok => Either.cond(ok, (), AppError(ErrorCode.VoicePeerNotFound)))
        case _ => IO.pure(Left(AppError(ErrorCode.VoiceNotJoined)))
      }
  }

  /** チャンネルのボイスを外した・アーカイブしたときに、いる人を外す. */
  def channelChanged(id: UUID): IO[Unit] =
    channels.find(id).flatMap(_.traverse_(c => voice.clear(id).whenA(!c.voice || c.archived)))

  /** 状態が届かなくなった接続と、古い取り決めを消す（全ノードで動かす. 消すだけなので重なっても問題ない）. */
  def loop: IO[Nothing] =
    sbirds.db.Periodic.run(15.seconds, 15.seconds, ErrorCode.VoiceCleanupFailed)(
      IO.realTimeInstant.flatMap(now => voice.expire(now.minusMillis(Timeout.toMillis))) *> voice.purgeSignals(60).void
    )
}
