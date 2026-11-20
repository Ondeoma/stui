package stui.app

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import stui.api.{MattermostImportOptions, MattermostImporter, MattermostSource}
import stui.db.ImportStore
import sbirds.storage.S3BlobStore

import java.nio.file.{Files, Path}

/**
 * Mattermost から取り込むコマンド（サーバーとは別に動かす）。
 * 取り込み先は stui と同じ環境変数（DATABASE_*・S3_*）。取り込み元は Mattermost の DB のダンプを戻した PostgreSQL。
 * 手順は README の「Mattermost からの取り込み」。
 */
object ImportMattermost extends IOApp {

  private val usage =
    """使い方: stui.app.ImportMattermost [--team <チームの名前>]… [--files <ディレクトリ>] [--link-by-email] [--dry-run]
      |  --team     取り込むチーム（Mattermost のチームの名前. URL に出るもの）。複数指定できる。省くとすべて
      |  --files    Mattermost のファイルの置き場所（config.json の FileSettings.Directory. 例: /opt/mattermost/data）。添付とカスタム絵文字を取り込む
      |  --link-by-email  メールアドレスが未確認の既存のユーザーにも、同じメールアドレスで結び付ける（既定は、IdP が確かめ済みの人だけ）
      |  --dry-run  書き込まずに、取り込む数だけを表示する
      |取り込み元: MATTERMOST_DATABASE_URL・MATTERMOST_DATABASE_USER・MATTERMOST_DATABASE_PASSWORD
      |取り込み先: stui と同じ DATABASE_*・S3_*""".stripMargin

  private def parseArgs(args: List[String],
                        o: MattermostImportOptions =
                          MattermostImportOptions()): Either[String, MattermostImportOptions] =
    args match {
      case Nil => Right(o)
      case "--team" :: t :: rest => parseArgs(rest, o.copy(teams = o.teams :+ t))
      case "--files" :: d :: rest =>
        val p = Path.of(d).toAbsolutePath.normalize()
        if Files.isDirectory(p) then parseArgs(rest, o.copy(filesDir = Some(p))) else Left(s"ディレクトリがありません: $d")
      case "--link-by-email" :: rest => parseArgs(rest, o.copy(linkByEmail = true))
      case "--dry-run" :: rest => parseArgs(rest, o.copy(dryRun = true))
      case x :: _ => Left(s"分からない引数です: $x")
    }

  private def env(name: String): Either[String, String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"環境変数 $name を設定してください")

  def run(args: List[String]): IO[ExitCode] =
    (
      parseArgs(args),
      (env("MATTERMOST_DATABASE_URL"), env("MATTERMOST_DATABASE_USER"), env("MATTERMOST_DATABASE_PASSWORD")).tupled,
      Config.db
    )
      .tupled match {
      case Left(e) => IO.println(s"$e\n\n$usage").as(ExitCode(2))
      case Right((opts, (url, user, pass), target)) =>
        val resources =
          for {
            srcXa <- Database.transactor(DbConfig(url, user, pass, 2))
            xa <- Database.transactor(target.copy(maxPoolSize = 4))
            blobs <-
              (if opts.dryRun then None else Config.s3).traverse(c => S3BlobStore.resource(c).evalTap(_.ensureBucket))
          } yield (srcXa, xa, blobs)
        (if opts.dryRun then IO.unit else Database.migrate(target).void) *>
          resources.use {
            (srcXa, xa, blobs) =>
              new MattermostImporter(new MattermostSource(srcXa), new ImportStore(xa, "mattermost"), blobs, IO.println)
                .run(opts).flatMap { r =>
                  IO.println(
                    s"""${if opts.dryRun then "取り込む数（書き込んでいません）" else "取り込みました"}:
                       |  ユーザー ${r.users}（既存の人に結び付け ${r.linkedUsers}）・チャンネル ${r.channels}・メンバー ${r.members}
                       |  投稿 ${r.posts}・リアクション ${r.reactions}・添付 ${r.files}（見つからない ${r.missingFiles}）・カスタム絵文字 ${r.emoji}
                       |  取り込み済み・取り込まないもの ${r.skipped}""".stripMargin
                  )
                }.as(ExitCode.Success)
          }.handleErrorWith(e => IO.println(s"取り込みに失敗しました: ${e.getMessage}").as(ExitCode.Error))
    }
}
