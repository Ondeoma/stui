package stui.api

import java.nio.file.Path

// ------------------------------------------------------------------ 取り込み元（Mattermost の PostgreSQL. ダンプを戻したもの）

final case class MmTeam(id: String, name: String, displayName: String)
final case class MmUser(id: String,
                        username: String,
                        email: String,
                        first: String,
                        last: String,
                        nickname: String,
                        createAt: Long,
                        deleteAt: Long,
                        isBot: Boolean)
final case class MmChannel(id: String,
                           teamId: String,
                           tpe: String,
                           displayName: String,
                           header: String,
                           purpose: String,
                           creatorId: String,
                           createAt: Long,
                           deleteAt: Long)
final case class MmMember(channelId: String, userId: String, admin: Boolean, lastViewedAt: Long)
final case class MmPost(id: String,
                        channelId: String,
                        userId: String,
                        rootId: String,
                        message: String,
                        tpe: String,
                        props: String,
                        createAt: Long,
                        editAt: Long,
                        pinned: Boolean)
final case class MmReaction(postId: String, userId: String, emoji: String, createAt: Long)
final case class MmFile(id: String,
                        postId: String,
                        path: String,
                        name: String,
                        size: Long,
                        mime: String,
                        creatorId: String)
final case class MmEmoji(id: String, name: String, creatorId: String)

// ------------------------------------------------------------------ 取り込み

/** teams: 取り込むチーム（名前. 空ならすべて）。filesDir: Mattermost のファイルの置き場所（FileSettings.Directory. 添付・カスタム絵文字）。 */
final case class MattermostImportOptions(teams: List[String] = Nil,
                                         filesDir: Option[Path] = None,
                                         dryRun: Boolean = false)
