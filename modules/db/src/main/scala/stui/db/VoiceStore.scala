package stui.db

import sbirds.db.*

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import stui.shared.*

import java.time.Instant
import java.util.UUID

/** 届ける取り決め（WebRTC の SDP・ICE の候補）. */
final case class VoiceSignalRow(id: Long, toUser: UUID, toConn: UUID, fromConn: UUID, data: String)

/** ボイスチャンネルにいる人（接続）. */
final case class VoiceConn(connId: UUID, channelId: UUID, userId: UUID)

/**
 * ボイスチャンネルの顔ぶれと、取り決めの受け渡し. 顔ぶれ・状態が変わったら変更履歴（voice.changed）を残し、
 * チャンネルのメンバーの画面が読み直せるようにする.
 */
final class VoiceStore(xa: Transactor[IO]) extends TxStore(xa) {

  private def changedC(channelId: UUID, user: UUID): ConnectionIO[Unit] =
    sql"""insert into chat_event (channel_id, kind, actor_id, user_id)
          values ($channelId, ${EventKind.VoiceChanged}, $user, $user)""".update.run.void

  /**
   * 入る（新しい接続）. 同じ人の前の接続（ほかのチャンネル・タブ）は外す.
   * 前の接続のチャンネルを返す（移ったことを知らせるため）.
   */
  def join(connId: UUID, channelId: UUID, user: UUID, muted: Boolean, deafened: Boolean): IO[Option[UUID]] = tx {
    for {
      old <- sql"delete from voice_member where user_id = $user returning channel_id".query[UUID].option
      _ <- old.traverse_(changedC(_, user))
      _ <- sql"""insert into voice_member (conn_id, channel_id, user_id, muted, deafened)
                 values ($connId, $channelId, $user, $muted, $deafened)""".update.run
      _ <- changedC(channelId, user)
    } yield old
  }

  /** 出る. 出たチャンネル（その接続が無ければ None）. */
  def leave(connId: UUID, user: UUID): IO[Option[UUID]] = tx {
    sql"delete from voice_member where conn_id = $connId and user_id = $user returning channel_id".query[UUID].option
      .flatTap(_.traverse_(changedC(_, user)))
  }

  /** 状態を変える（届いたことも記録する）. 接続が無ければ false（外れている）. 状態が変わったときだけ変更履歴を残す. */
  def update(connId: UUID, user: UUID, muted: Boolean, deafened: Boolean, sharing: Boolean): IO[Boolean] = tx {
    sql"""select channel_id, muted, deafened, sharing from voice_member where conn_id = $connId and user_id = $user for update"""
      .query[(UUID, Boolean, Boolean, Boolean)].option.flatMap {
        case None => false.pure[ConnectionIO]
        case Some((channel, m, d, sh)) =>
          sql"""update voice_member set muted = $muted, deafened = $deafened, sharing = $sharing, seen_at = now()
                where conn_id = $connId""".update.run *>
            changedC(channel, user).whenA((m, d, sh) != (muted, deafened, sharing)).as(true)
      }
  }

  def conn(connId: UUID): IO[Option[VoiceConn]] =
    tx(sql"select conn_id, channel_id, user_id from voice_member where conn_id = $connId".query[VoiceConn].option)

  /** 状態が届かなくなった接続（画面を閉じた・通信が切れた）を外す. 外した数. */
  def expire(before: Instant): IO[Int] = tx {
    sql"delete from voice_member where seen_at < $before returning channel_id, user_id".query[(UUID, UUID)].to[List]
      .flatTap(_.traverse_((c, u) => changedC(c, u))).map(_.size)
  }

  /** チャンネルからボイスを外したとき・アーカイブしたときに、いる人を外す. */
  def clear(channelId: UUID): IO[Int] = tx {
    sql"delete from voice_member where channel_id = $channelId returning user_id".query[UUID].to[List]
      .flatTap(us => us.headOption.traverse_(changedC(channelId, _))).map(_.size)
  }

  private val memberCols =
    fr"select v.channel_id, v.conn_id, u.id, u.name, u.avatar_url, u.is_bot, v.muted, v.deafened, v.sharing, v.joined_at"

  private def rooms(rows: List[(UUID, VoiceMemberDto)]): List[VoiceRoomDto] =
    rows.groupMap(_._1)(_._2).toList.map((c, ms) => VoiceRoomDto(c, ms.sortBy(_.joinedAt))).sortBy(_.channelId)

  /** 自分が参加しているチャンネル（と extra）のボイスチャンネルにいる人. 誰もいないチャンネルは含めない. */
  def roomsFor(user: UUID, extra: Option[UUID]): IO[List[VoiceRoomDto]] = tx {
    (memberCols ++ fr"""from voice_member v join app_user u on u.id = v.user_id
         where v.channel_id in (select channel_id from channel_member where user_id = $user)""" ++
      extra.fold(Fragment.empty)(c => fr"or v.channel_id = $c") ++ fr"order by v.joined_at")
      .query[(UUID, VoiceMemberDto)].to[List].map(rooms)
  }

  def room(channelId: UUID): IO[VoiceRoomDto] = tx {
    (memberCols ++
      fr"from voice_member v join app_user u on u.id = v.user_id where v.channel_id = $channelId order by v.joined_at")
      .query[(UUID, VoiceMemberDto)].to[List].map(rs => rooms(rs).headOption.getOrElse(VoiceRoomDto(channelId, Nil)))
  }

  // ---------------------------------------------------------------- 取り決めの受け渡し

  /** 同じボイスチャンネルの相手に送る. 相手がいなければ false. */
  def signal(from: UUID, to: UUID, data: String): IO[Boolean] = tx {
    sql"""insert into voice_signal (to_user, to_conn, from_conn, data)
          select t.user_id, t.conn_id, f.conn_id, $data from voice_member f join voice_member t on t.channel_id = f.channel_id
          where f.conn_id = $from and t.conn_id = $to and t.conn_id <> f.conn_id""".update.run.map(_ > 0)
  }

  /**
   * 最近（within 秒以内）の取り決め. 番号は書き込みの確定の順と前後することがあるため、番号ではなく時刻の範囲で読み、
   * 配った番号は呼ぶ側で覚えておく.
   */
  def recentSignals(withinSeconds: Int, user: Option[UUID] = None): IO[List[VoiceSignalRow]] = tx {
    (fr"select id, to_user, to_conn, from_conn, data from voice_signal where at > now() - $withinSeconds * interval '1 second'" ++
      user.fold(Fragment.empty)(u => fr"and to_user = $u") ++ fr"order by id").query[VoiceSignalRow].to[List]
  }

  /** 古い取り決めを消す. */
  def purgeSignals(olderThanSeconds: Int): IO[Int] =
    tx(sql"delete from voice_signal where at < now() - $olderThanSeconds * interval '1 second'".update.run)
}
