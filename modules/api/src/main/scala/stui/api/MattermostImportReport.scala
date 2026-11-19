package stui.api

/** 取り込んだ数（skipped は取り込み済み・取り込めなかったもの）。 */
final case class MattermostImportReport(
  users: Int = 0,
  linkedUsers: Int = 0,
  channels: Int = 0,
  members: Int = 0,
  posts: Int = 0,
  reactions: Int = 0,
  files: Int = 0,
  missingFiles: Int = 0,
  emoji: Int = 0,
  skipped: Int = 0)

object MattermostImportReport {
  given cats.Monoid[MattermostImportReport] with {
    def empty = MattermostImportReport()
    def combine(
      a: MattermostImportReport,
      b: MattermostImportReport
    ) = MattermostImportReport(
      a.users + b.users,
      a.linkedUsers + b.linkedUsers,
      a.channels + b.channels,
      a.members + b.members,
      a.posts + b.posts,
      a.reactions + b.reactions,
      a.files + b.files,
      a.missingFiles + b.missingFiles,
      a.emoji + b.emoji,
      a.skipped + b.skipped
    )
  }
}
