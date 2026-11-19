package stui.shared

import io.circe.*

import java.util.UUID

// バックエンド(JVM)と画面(Scala.js)の両方で使う API の型。
// 日時は Timestamp（JVM では Instant）、日付は "yyyy-MM-dd" の文字列（Scala.js で java.time を使わないため）。

/** チャンネルの種類. */
object ChannelKind {
  val Public = "public"
  val Private = "private"
  val Dm = "dm"
  val Group = "group"
  val all: List[String] = List(Public, Private, Dm, Group)
  def isDm(k: String): Boolean = k == Dm || k == Group
  def icon(k: String): String = k match {
    case Public => "#"
    case Private => "🔒"
    case Group => "👥"
    case _ => "@"
  }
}

/** チャンネルでの役割. */
object ChannelRole {
  val Member = "member"
  val Admin = "admin"
}

/** チャンネルの通知の設定. default は「DM はすべて、それ以外はメンションだけ」. */
object NotifyLevel {
  val Default = "default"
  val All = "all"
  val Mention = "mention"
  val None = "none"
  val all: List[String] = List(Default, All, Mention, None)
  def label(l: String): String = l match {
    case Default => "既定（DM はすべて・チャンネルはメンションだけ）"
    case All => "すべてのメッセージ"
    case Mention => "メンションだけ"
    case None => "通知しない"
    case _ => l
  }
}

/** 通知の種類. */
object NotificationKind {
  val Mention = "mention"
  val Dm = "dm"
  val Thread = "thread"
  val Channel = "channel"
  val Reminder = "reminder"
  val App = "app"
  val all: List[String] = List(Mention, Dm, Thread, Channel, Reminder, App)
  def label(k: String): String = k match {
    case Mention => "メンション"
    case Dm => "ダイレクトメッセージ"
    case Thread => "スレッドの返信"
    case Channel => "チャンネルの新着"
    case Reminder => "リマインダー"
    case App => "連携サービス"
    case _ => k
  }
}

object ApiScope {
  val Read = "read"
  val Write = "write"
  val Admin = "admin"
  val all: List[String] = List(Read, Write, Admin)
  def label(s: String): String = s match {
    case Read => "参照"
    case Write => "投稿・更新・削除"
    case Admin => "管理"
    case _ => s
  }
}

/** 画面の同期・Webhook のイベント名. */
object EventKind {
  val MessageCreated = "message.created"
  val MessageUpdated = "message.updated"
  val MessageDeleted = "message.deleted"
  val ReactionChanged = "reaction.changed"
  val ChannelCreated = "channel.created"
  val ChannelUpdated = "channel.updated"
  val MemberJoined = "member.joined"
  val MemberLeft = "member.left"
  val BookmarkChanged = "bookmark.changed"

  /** 送信の Webhook で選べるイベント. */
  val webhook: List[String] =
    List(MessageCreated, MessageUpdated, MessageDeleted, ReactionChanged, ChannelCreated, MemberJoined, MemberLeft)
  def label(k: String): String = k match {
    case MessageCreated => "メッセージの投稿"
    case MessageUpdated => "メッセージの編集"
    case MessageDeleted => "メッセージの削除"
    case ReactionChanged => "リアクション"
    case ChannelCreated => "チャンネルの作成"
    case ChannelUpdated => "チャンネルの変更"
    case MemberJoined => "メンバーの参加"
    case MemberLeft => "メンバーの退出"
    case _ => k
  }
}

// ------------------------------------------------------------------ ユーザー

final case class UserBrief(id: UUID, name: String, avatarUrl: Option[String], bot: Boolean) derives Encoder.AsObject,
    Decoder

final case class UserDto(id: UUID,
                         name: String,
                         email: Option[String],
                         avatarUrl: Option[String],
                         isAdmin: Boolean,
                         disabled: Boolean,
                         bot: Boolean,
                         lastLoginAt: Option[Timestamp],
                         groups: List[String]) derives Encoder.AsObject, Decoder

final case class MeDto(
  id: UUID,
  name: String,
  email: Option[String],
  avatarUrl: Option[String],
  isAdmin: Boolean,
  timezone: String,
  via: String,
  scopes: List[String],
  /** sbirds のアプリの表示名（slug → 名前. sruru の ID トークンから. 自身は "_self"） */
  appNames: Map[String, String] = Map.empty) derives Encoder.AsObject, Decoder

final case class UpdateMeReq(name: Option[String], timezone: Option[String]) derives Encoder.AsObject, Decoder

final case class UpdateUserReq(isAdmin: Option[Boolean], disabled: Option[Boolean]) derives Encoder.AsObject, Decoder

final case class NotifyPrefDto(kind: String, email: Boolean) derives Encoder.AsObject, Decoder

final case class ApiKeyDto(id: UUID,
                           name: String,
                           prefix: String,
                           scopes: List[String],
                           createdAt: Timestamp,
                           expiresAt: Option[Timestamp],
                           lastUsedAt: Option[Timestamp],
                           client: Option[String]) derives Encoder.AsObject, Decoder

final case class CreateApiKeyReq(name: String, scopes: List[String], expiresInDays: Option[Int])
  derives Encoder.AsObject, Decoder

final case class CreatedApiKeyDto(key: String, info: ApiKeyDto) derives Encoder.AsObject, Decoder

/** エージェント専用アカウントの API キー（管理画面。取り消したものも含む。ハッシュは返さない）. */
final case class AgentKeyDto(id: UUID,
                             name: String,
                             prefix: String,
                             scopes: List[String],
                             createdAt: Timestamp,
                             lastUsedAt: Option[Timestamp],
                             expiresAt: Option[Timestamp],
                             revoked: Boolean) derives Encoder.AsObject, Decoder

/** エージェント専用アカウント（管理画面）. channels は参加しているチャンネル. */
final case class AgentDto(id: UUID,
                          name: String,
                          disabled: Boolean,
                          createdAt: Timestamp,
                          keys: List[AgentKeyDto],
                          channels: List[ChannelBrief])
  derives Encoder.AsObject, Decoder

final case class CreateAgentReq(name: String) derives Encoder.AsObject, Decoder

final case class UpdateAgentReq(name: Option[String], disabled: Option[Boolean]) derives Encoder.AsObject, Decoder

/** エージェント専用アカウントをチャンネルに参加させる. */
final case class AgentChannelsReq(channelIds: List[UUID]) derives Encoder.AsObject, Decoder

/** まとめてメンションできる相手（id は g:<グループID>、r:channel・r:here）. */
final case class MentionGroupDto(id: String, name: String, count: Int) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ チャンネル

final case class ChannelBrief(id: UUID, name: String, kind: String) derives Encoder.AsObject, Decoder

/**
 * チャンネル. member 以下は自分にとっての値（参加していなければ member = false）.
 * dmUsers は DM・グループ DM の自分以外の参加者（自分だけの DM なら自分）.
 */
final case class ChannelDto(id: UUID,
                            name: String,
                            kind: String,
                            topic: String,
                            purpose: String,
                            archived: Boolean,
                            autoJoin: Boolean,
                            memberCount: Int,
                            createdAt: Timestamp,
                            lastMessageAt: Option[Timestamp],
                            member: Boolean,
                            role: Option[String],
                            favorite: Boolean,
                            muted: Boolean,
                            notifyLevel: String,
                            unread: Int,
                            mentions: Int,
                            lastReadSeq: Long,
                            dmUsers: List[UserBrief]) derives Encoder.AsObject, Decoder

final case class CreateChannelReq(name: String,
                                  kind: String,
                                  topic: Option[String],
                                  purpose: Option[String],
                                  memberIds: List[UUID])
  derives Encoder.AsObject, Decoder

final case class UpdateChannelReq(name: Option[String],
                                  topic: Option[String],
                                  purpose: Option[String],
                                  archived: Option[Boolean],
                                  autoJoin: Option[Boolean]) derives Encoder.AsObject, Decoder

final case class MemberPrefReq(favorite: Option[Boolean],
                               muted: Option[Boolean],
                               notifyLevel: Option[String],
                               hidden: Option[Boolean])
  derives Encoder.AsObject, Decoder

final case class ChannelMemberDto(user: UserBrief, role: String, joinedAt: Timestamp) derives Encoder.AsObject, Decoder

final case class AddMembersReq(userIds: List[UUID]) derives Encoder.AsObject, Decoder

final case class SetRoleReq(role: String) derives Encoder.AsObject, Decoder

/** DM を開く（相手. 1人なら1対1、2人以上ならグループ DM、空なら自分だけの DM）. */
final case class OpenDmReq(userIds: List[UUID]) derives Encoder.AsObject, Decoder

final case class ReadReq(seq: Long) derives Encoder.AsObject, Decoder

final case class BookmarkDto(id: UUID,
                             channelId: UUID,
                             title: String,
                             url: String,
                             emoji: String,
                             createdBy: Option[UserBrief])
  derives Encoder.AsObject, Decoder

final case class BookmarkReq(title: String, url: String, emoji: Option[String]) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ メッセージ

final case class AttachmentDto(id: UUID, fileName: String, size: Long, contentType: String, url: String)
  derives Encoder.AsObject, Decoder

/** メッセージへのリアクション（絵文字と、付けた人）. */
final case class ReactionDto(emoji: String, users: List[UserBrief]) derives Encoder.AsObject, Decoder
final case class ReactionReq(emoji: String) derives Encoder.AsObject, Decoder

/**
 * メッセージ. kind は user（人）・system（参加・退出など）・bot（Webhook・連携サービス・リマインダー）.
 * props は付加情報（会議 meeting・Webhook の表示名 username / iconUrl・連携サービス app など）.
 * 削除したメッセージは、スレッドの返信が残っていれば deleted = true で本文を空にして返す.
 */
final case class MessageDto(id: UUID,
                            channelId: UUID,
                            seq: Long,
                            rootId: Option[UUID],
                            alsoChannel: Boolean,
                            author: Option[UserBrief],
                            kind: String,
                            via: String,
                            body: Json,
                            html: String,
                            props: Json,
                            createdAt: Timestamp,
                            editedAt: Option[Timestamp],
                            deleted: Boolean,
                            replyCount: Int,
                            lastReplyAt: Option[Timestamp],
                            replyUsers: List[UserBrief],
                            reactions: List[ReactionDto],
                            pinned: Boolean,
                            saved: Boolean,
                            attachments: List[AttachmentDto]) derives Encoder.AsObject, Decoder

/** 投稿. body は Tiptap の文書（API では Markdown の文字列も可）. attachmentIds は先にアップロードした添付. */
final case class PostMessageReq(body: Json,
                                rootId: Option[UUID] = None,
                                alsoChannel: Option[Boolean] = None,
                                attachmentIds: Option[List[UUID]] = None) derives Encoder.AsObject, Decoder

final case class EditMessageReq(body: Json) derives Encoder.AsObject, Decoder

/** メッセージの一覧（新しい順に limit 件を古い順に並べたもの）. hasMore はさらに古いものがあるか. */
final case class MessagePageDto(messages: List[MessageDto], hasMore: Boolean, hasNewer: Boolean)
  derives Encoder.AsObject, Decoder

final case class ThreadDto(root: MessageDto, replies: List[MessageDto], following: Boolean, channel: ChannelBrief)
  derives Encoder.AsObject, Decoder

/** フォロー中のスレッドの一覧の1件. */
final case class ThreadSummaryDto(root: MessageDto, channel: ChannelBrief, unread: Int, lastReplies: List[MessageDto])
  derives Encoder.AsObject, Decoder

final case class MessageIdsReq(ids: List[UUID]) derives Encoder.AsObject, Decoder

/** 保存したメッセージ・ピン留め・メンションの一覧の1件（どのチャンネルか付き）. */
final case class MessageInChannelDto(message: MessageDto, channel: ChannelBrief) derives Encoder.AsObject, Decoder

/** メッセージの下書き（ユーザー＋チャンネル＋スレッドごと）. */
final case class DraftDto(body: Json, updatedAt: Timestamp) derives Encoder.AsObject, Decoder

final case class DraftListItemDto(channel: ChannelBrief,
                                  rootId: Option[UUID],
                                  body: Json,
                                  text: String,
                                  updatedAt: Timestamp)
  derives Encoder.AsObject, Decoder

object Reactions {

  /** 付けられる絵文字（分類ごと）. カスタム絵文字は ":名前:" で付ける. */
  val categories: List[(String, List[String])] = List(
    "よく使う" -> List("👍", "❤️", "😄", "🎉", "🙏", "👀", "🚀", "✅", "🤔", "😢", "👏", "🔥", "💯", "🙌", "👌", "✨"),
    "顔" -> List(
      "😀",
      "😃",
      "😁",
      "😆",
      "😅",
      "🤣",
      "😂",
      "🙂",
      "😉",
      "😊",
      "😇",
      "🥰",
      "😍",
      "🤩",
      "😘",
      "😋",
      "😛",
      "😜",
      "🤪",
      "🤗",
      "🤭",
      "🤫",
      "🤐",
      "😐",
      "😑",
      "😶",
      "😏",
      "😒",
      "🙄",
      "😬",
      "😌",
      "😔",
      "😪",
      "😴",
      "😷",
      "🤒",
      "🥵",
      "🥶",
      "😵",
      "🤯",
      "🥳",
      "😎",
      "🤓",
      "🧐",
      "😕",
      "😟",
      "🙁",
      "😮",
      "😯",
      "😲",
      "😳",
      "🥺",
      "😦",
      "😨",
      "😰",
      "😥",
      "😭",
      "😱",
      "😖",
      "😣",
      "😞",
      "😓",
      "😩",
      "😫",
      "🥱",
      "😤",
      "😡",
      "😠",
      "🤬",
      "😈",
      "💀",
      "🤡",
      "👻",
      "🤖"
    ),
    "手" -> List(
      "👋",
      "✋",
      "🖐️",
      "👌",
      "🤌",
      "✌️",
      "🤞",
      "🤟",
      "🤘",
      "🤙",
      "👈",
      "👉",
      "👆",
      "👇",
      "☝️",
      "👍",
      "👎",
      "✊",
      "👊",
      "🤛",
      "🤜",
      "👏",
      "🙌",
      "👐",
      "🤲",
      "🤝",
      "🙏",
      "✍️",
      "💪",
      "🫡",
      "🫶"
    ),
    "記号" -> List(
      "❤️",
      "🧡",
      "💛",
      "💚",
      "💙",
      "💜",
      "🖤",
      "🤍",
      "💔",
      "❣️",
      "💕",
      "💯",
      "💢",
      "💥",
      "💫",
      "💦",
      "💨",
      "💬",
      "💭",
      "💤",
      "✅",
      "☑️",
      "✔️",
      "❌",
      "❎",
      "⭕",
      "❗",
      "❓",
      "⁉️",
      "‼️",
      "⚠️",
      "🚫",
      "⛔",
      "🆗",
      "🆕",
      "🆙",
      "🔴",
      "🟠",
      "🟡",
      "🟢",
      "🔵",
      "🟣",
      "⬆️",
      "⬇️",
      "➡️",
      "⬅️",
      "🔁",
      "➕",
      "➖"
    ),
    "もの" -> List(
      "🎉",
      "🎊",
      "🎈",
      "🎁",
      "🏆",
      "🥇",
      "🏅",
      "⭐",
      "🌟",
      "✨",
      "🔥",
      "⚡",
      "💡",
      "📌",
      "📎",
      "📝",
      "📅",
      "⏰",
      "⏳",
      "🔒",
      "🔑",
      "🛠️",
      "🔧",
      "⚙️",
      "🧪",
      "🐛",
      "🚀",
      "🛸",
      "💻",
      "📱",
      "📦",
      "📈",
      "📉",
      "📊",
      "💰",
      "☕",
      "🍵",
      "🍺",
      "🍣",
      "🍙",
      "🍰",
      "🍕"
    ),
    "自然" -> List(
      "🌸",
      "🌻",
      "🍀",
      "🌱",
      "🌈",
      "☀️",
      "🌙",
      "⛅",
      "☔",
      "❄️",
      "🌊",
      "🐶",
      "🐱",
      "🐼",
      "🐧",
      "🐢",
      "🦄",
      "🐝",
      "🦋"
    )
  )

  val all: List[String] = categories.flatMap(_._2).distinct

  /** カスタム絵文字（:名前:）の名前. */
  def customName(e: String): Option[String] =
    Option(e).filter(_.matches("^:[a-z0-9_+-]{2,32}:$")).map(_.drop(1).dropRight(1))

  /** リアクションとして付けられるか（決まった絵文字か :name:）. */
  def valid(e: String): Boolean = all.contains(e) || customName(e).isDefined
}

/** カスタム絵文字. url は画像（ログインが必要）. */
final case class CustomEmojiDto(name: String, url: String, createdBy: Option[String]) derives Encoder.AsObject, Decoder
object CustomEmoji {
  def url(name: String): String = s"/api/emoji/$name/image"
}

// ------------------------------------------------------------------ 画面の同期

final case class ChatEventDto(id: Long,
                              channelId: UUID,
                              kind: String,
                              messageId: Option[UUID],
                              rootId: Option[UUID],
                              actorId: Option[UUID],
                              userId: Option[UUID]) derives Encoder.AsObject, Decoder

final case class ChangesDto(events: List[ChatEventDto], latest: Long) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ リマインダー・定期メッセージ

final case class MessageRefDto(id: UUID,
                               channel: ChannelBrief,
                               author: Option[UserBrief],
                               text: String,
                               rootId: Option[UUID])
  derives Encoder.AsObject, Decoder

final case class ReminderDto(id: UUID,
                             text: String,
                             remindAt: Timestamp,
                             message: Option[MessageRefDto],
                             fired: Boolean,
                             done: Boolean,
                             createdAt: Timestamp) derives Encoder.AsObject, Decoder

/** リマインダーを作る. messageId があればそのメッセージについて. */
final case class ReminderReq(text: Option[String], messageId: Option[UUID], remindAt: Timestamp)
  derives Encoder.AsObject, Decoder

final case class ReminderPatch(remindAt: Option[Timestamp], done: Option[Boolean]) derives Encoder.AsObject, Decoder

object Frequency {
  val Once = "once"
  val Daily = "daily"
  val Weekdays = "weekdays"
  val Weekly = "weekly"
  val Monthly = "monthly"
  val all: List[String] = List(Once, Daily, Weekdays, Weekly, Monthly)
  def label(f: String): String = f match {
    case Once => "1回だけ"
    case Daily => "毎日"
    case Weekdays => "平日（月〜金）"
    case Weekly => "毎週"
    case Monthly => "毎月"
    case _ => f
  }
  val weekdays: List[(Int, String)] = List(1 -> "月", 2 -> "火", 3 -> "水", 4 -> "木", 5 -> "金", 6 -> "土", 7 -> "日")
}

final case class ScheduledDto(id: UUID,
                              channel: ChannelBrief,
                              rootId: Option[UUID],
                              author: Option[UserBrief],
                              body: Json,
                              html: String,
                              frequency: String,
                              runDate: Option[String],
                              runTime: String,
                              weekday: Option[Int],
                              monthday: Option[Int],
                              timezone: String,
                              nextRun: Option[Timestamp],
                              enabled: Boolean,
                              lastRunAt: Option[Timestamp],
                              lastError: Option[String]) derives Encoder.AsObject, Decoder

final case class ScheduledReq(channelId: UUID,
                              body: Json,
                              frequency: String,
                              runDate: Option[String],
                              runTime: String,
                              weekday: Option[Int],
                              monthday: Option[Int],
                              enabled: Boolean,
                              rootId: Option[UUID] = None) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 検索

final case class SearchHitDto(message: MessageDto, channel: ChannelBrief, snippet: Option[String], fuzzy: Boolean)
  derives Encoder.AsObject, Decoder

final case class SearchResultDto(hits: List[SearchHitDto],
                                 total: Int,
                                 channels: List[ChannelDto],
                                 users: List[UserBrief],
                                 files: List[FileHitDto],
                                 error: Option[String]) derives Encoder.AsObject, Decoder

final case class FileHitDto(attachment: AttachmentDto,
                            channel: ChannelBrief,
                            messageId: Option[UUID],
                            createdBy: Option[UserBrief],
                            createdAt: Timestamp) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 通知

final case class NotificationDto(id: UUID,
                                 kind: String,
                                 channel: Option[ChannelBrief],
                                 messageId: Option[UUID],
                                 rootId: Option[UUID],
                                 actor: Option[UserBrief],
                                 title: String,
                                 body: String,
                                 at: Timestamp,
                                 read: Boolean) derives Encoder.AsObject, Decoder

final case class NotificationListDto(items: List[NotificationDto], unread: Int) derives Encoder.AsObject, Decoder
final case class MarkReadReq(ids: List[UUID]) derives Encoder.AsObject, Decoder
final case class UnreadDto(unread: Int, mentions: Int) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ Webhook

final case class IncomingHookDto(id: UUID,
                                 channel: ChannelBrief,
                                 name: String,
                                 username: Option[String],
                                 iconUrl: Option[String],
                                 iconEmoji: Option[String],
                                 tokenHint: String,
                                 enabled: Boolean,
                                 createdBy: Option[UserBrief],
                                 createdAt: Timestamp) derives Encoder.AsObject, Decoder

/** 作成した直後だけ URL（トークン入り）を返す. */
final case class CreatedIncomingHookDto(hook: IncomingHookDto, url: String) derives Encoder.AsObject, Decoder

final case class IncomingHookReq(channelId: UUID,
                                 name: String,
                                 username: Option[String],
                                 iconUrl: Option[String],
                                 enabled: Boolean,
                                 iconEmoji: Option[String] = None) derives Encoder.AsObject, Decoder

/** 受信の Webhook に選べるアイコン（決まった絵文字と、カスタム絵文字 :name:）. */
object HookIcons {
  val all: List[(String, String)] = List(
    "🤖" -> "ボット",
    "🔔" -> "お知らせ",
    "🚀" -> "デプロイ",
    "✅" -> "成功",
    "⚠️" -> "警告",
    "🚨" -> "障害",
    "🐛" -> "バグ",
    "🛠️" -> "ビルド",
    "📦" -> "リリース",
    "📈" -> "グラフ",
    "📅" -> "予定",
    "📣" -> "告知",
    "💬" -> "チャット",
    "🔒" -> "セキュリティ",
    "📝" -> "メモ",
    "🌐" -> "Web"
  )
  def valid(e: String): Boolean = all.exists(_._1 == e) || Reactions.customName(e).isDefined

  /** 送られてきた icon_emoji（Slack と同じ書き方）を表示する形にする。名前の分かる :name: は絵文字の文字にする。 */
  def normalize(e: String): String = EmojiNames.fromShortcode(e).getOrElse(e.trim)

  /** 文字のままアイコンに描けるか（短い絵文字だけ。英字・:・空白を含むものは枠からはみ出すので描かない）. */
  def drawable(e: String): Boolean =
    e.nonEmpty && e.codePointCount(0, e.length) <= 8 && e.exists(_ >= 128) &&
      !e.exists(c => (c < 128 && c.isLetter) || c == ':' || c.isWhitespace)
}

final case class OutgoingHookDto(id: UUID,
                                 channel: Option[ChannelBrief],
                                 name: String,
                                 url: String,
                                 secret: String,
                                 events: List[String],
                                 triggerWords: List[String],
                                 enabled: Boolean,
                                 createdBy: Option[UserBrief],
                                 createdAt: Timestamp) derives Encoder.AsObject, Decoder

final case class OutgoingHookReq(channelId: Option[UUID],
                                 name: String,
                                 url: String,
                                 events: List[String],
                                 triggerWords: List[String],
                                 enabled: Boolean) derives Encoder.AsObject, Decoder

final case class DeliveryDto(id: UUID,
                             event: String,
                             attempts: Int,
                             statusCode: Option[Int],
                             lastError: Option[String],
                             doneAt: Option[Timestamp],
                             failed: Boolean,
                             createdAt: Timestamp,
                             payload: Json) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 会議（skaka）

final case class MeetingReq(name: Option[String], rootId: Option[UUID]) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 運用

final case class AuditDto(id: Long,
                          at: Timestamp,
                          actor: Option[UserBrief],
                          via: String,
                          action: String,
                          target: Option[String],
                          detail: Json) derives Encoder.AsObject, Decoder

/** 画面の初期設定. meetings は skaka との連携が設定されているか. */
final case class ClientConfigDto(devLogin: Boolean,
                                 version: String,
                                 attachments: Boolean,
                                 mail: Boolean,
                                 linkServices: List[String],
                                 meetings: Boolean,
                                 maxUploadMb: Int) derives Encoder.AsObject, Decoder

// ------------------------------------------------------------------ 外部サービスのリンク（skiwi のページ・skawau のファイル・skea のタスクの名前を出す）

/** 管理者が設定したサービス. provider は skiwi・skawau・skea・skaka、connected は自分が許可したか. */
final case class LinkServiceDto(provider: String, name: String, url: String, connected: Boolean)
  derives Encoder.AsObject, Decoder

/** リンク先の名前（ページのタイトル・ファイル名・タスクのタイトル）と、前に付けるアイコン. id は UUID かタスクID. */
final case class LinkTitleDto(id: String, title: String, icon: Option[String]) derives Encoder.AsObject, Decoder

/** 見られるものだけ. connected が false なら連携が切れている. */
final case class LinkTitlesDto(items: List[LinkTitleDto], connected: Boolean) derives Encoder.AsObject, Decoder
final case class LinkTitlesReq(ids: List[String]) derives Encoder.AsObject, Decoder
