package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*

/** 検索（メッセージ・チャンネル・人・ファイル）。 */
final class SearchRoutes(search: SearchStore, channels: ChannelStore, users: UserStore, val sessions: SessionAuth, val access: Access2)
    extends RouteSupport:

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "search" =>
      authed(req) { s =>
        val q      = param(req, "q").getOrElse("")
        val limit  = intParam(req, "limit", 50, 200)
        val offset = param(req, "offset").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(0)
        SearchQuery.parse(q) match
          case Left(e) => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, Some(e)))
          case Right(query) if query.isEmpty => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, None))
          case Right(query) =>
            val words = query.text.mkString(" ")
            val extras =
              if offset > 0 || words.isEmpty then IO.pure((List.empty[ChannelDto], List.empty[UserBrief], List.empty[FileHitDto]))
              else
                (channels.browse(s.user.id, Some(words), archived = false, 8),
                  users.list(Some(words), includeDisabled = false, 8).map(_.map(u => UserBrief(u.id, u.name, u.avatarUrl, u.bot))),
                  search.files(s.user.id, query.text, 10)).tupled
            (search.search(s.user.id, Op.zone(s.user.timezone), query, limit, offset), extras).tupled.flatMap {
              case (Left(e), _)                       => Ok(SearchResultDto(Nil, 0, Nil, Nil, Nil, Some(e)))
              case (Right((hits, total)), (cs, us, fs)) => Ok(SearchResultDto(hits, total, cs, us, fs, None))
            }
      }
  }

/** 画面内の通知。 */
final class NotificationRoutes(activity: ActivityStore, val sessions: SessionAuth, val access: Access2) extends RouteSupport:

  private def kinds(req: Request[IO]): List[String] =
    param(req, "kinds").toList.flatMap(_.split(',').map(_.trim).filter(NotificationKind.all.contains))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "notifications" =>
      authed(req)(s => activity.list(s.user.id, kinds(req), param(req, "unread").contains("true"),
        param(req, "before").flatMap(b => scala.util.Try(Instant.parse(b)).toOption), intParam(req, "limit", 50, 200)).flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "notifications" / "unread" => authed(req)(s => activity.unread(s.user.id).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "notifications" / "read" =>
      authed(req)(s => body[MarkReadReq](req)(r => activity.markRead(s.user.id, r.ids.take(500)) *> NoContent()))

    case req @ POST -> Root / "api" / "notifications" / "read-all" =>
      authed(req)(s => body[Json](req)(j =>
        activity.markAllRead(s.user.id, j.hcursor.get[List[String]]("kinds").getOrElse(Nil).filter(NotificationKind.all.contains)) *> NoContent()))
  }

/** リマインダー・定期メッセージの送信（全ノードで動かし、SKIP LOCKED で取り合う）。 */
final class ScheduleService(store: ScheduleStore, messages: MessageStore, channels: ChannelStore, users: UserStore, chat: ChatService,
    notifier: NotifyService, sessions: SessionAuth):

  /** stui 自身のボット（リマインダーの送り主）。 */
  def systemBot: IO[User] = users.bot("bot:stui", "stui リマインダー")

  /** 次の送信日時。 */
  def nextRun(freq: String, runDate: Option[LocalDate], runTime: String, weekday: Option[Int], monthday: Option[Int], tz: String,
      after: Instant = Instant.now()): Option[Instant] =
    Recurrence.parseTime(runTime).flatMap(t => Recurrence.next(freq, runDate, t, weekday, monthday, Op.zone(tz), after))

  private def fireReminder(r: ReminderRow): IO[Unit] =
    for
      bot <- systemBot
      dm  <- channels.openDm(List(r.userId, bot.id))
      // 今も見られるメッセージだけを添える
      ref <- r.messageId.flatTraverse(id => sessions.sessionFor(r.userId).flatMap(_.flatTraverse(s =>
               chat.access.message(s, id, Need.Read).flatMap(_.toOption.flatTraverse(_ => messages.view(id, r.userId))))))
      text = r.text.trim
      md = List(
             Some("⏰ **リマインダー**" + (if text.nonEmpty then s": $text" else "")),
             ref.map(m => s"[メッセージを開く](/m/${m.id})")
           ).flatten.mkString("\n\n")
      res <- chat.publish(dm, Author(bot, "reminder"), RichText.fromMarkdown(md), None, false,
               Json.obj("reminder" -> Json.obj("id" -> r.id.asJson, "messageId" -> r.messageId.asJson)), "bot")
      _ <- res.toOption.traverse_(m =>
             notifier.send(NotificationKind.Reminder, r.userId, None, Some(dm.id), Some(m.id), "リマインダー", if text.nonEmpty then text else ref.map(m => RichText.toPlain(m.body).take(200)).getOrElse(""),
               Paths.message(dm.id, Some(m.id), None)))
    yield ()

  private def sendScheduled(r: ScheduledRow): IO[Option[String]] =
    sessions.sessionFor(r.authorId, AuthVia.System("schedule")).flatMap {
      case None => IO.pure(Some("作成した人が無効になっています"))
      case Some(s) => chat.post(s, r.channelId, PostMessageReq(r.body, r.rootId)).map(_.left.toOption.map(_.message))
    }

  def reminderLoop: IO[Nothing] =
    Periodic.run(15.seconds, 20.seconds)(e => s"[stui] reminder worker failed: $e")(store.processDueReminders(50)(fireReminder))

  def scheduledLoop: IO[Nothing] =
    Periodic.run(20.seconds, 30.seconds)(e => s"[stui] scheduled message worker failed: $e")(
      store.processDueScheduled(50)(sendScheduled)(r => nextRun(r.frequency, r.runDate, r.runTime, r.weekday, r.monthday, r.timezone)))

/** リマインダー・定期メッセージの API。 */
final class ScheduleRoutes(store: ScheduleStore, svc: ScheduleService, activity: ActivityStore, val sessions: SessionAuth, val access: Access2)
    extends RouteSupport:

  private def validate(s: AuthSession, r: ScheduledReq): Either[String, Option[LocalDate]] =
    for
      _ <- Either.cond(Recurrence.Frequencies.contains(r.frequency), (), "frequency は once・daily・weekdays・weekly・monthly です")
      _ <- Recurrence.parseTime(r.runTime).toRight("時刻は HH:mm で指定してください")
      d <- r.runDate.filter(_.trim.nonEmpty).traverse(v => scala.util.Try(LocalDate.parse(v.trim)).toOption.toRight("日付は yyyy-MM-dd で指定してください"))
      _ <- Either.cond(r.frequency != "once" || d.isDefined, (), "1回だけのときは日付を指定してください")
      _ <- Either.cond(r.frequency != "weekly" || r.weekday.exists(w => w >= 1 && w <= 7), (), "曜日（1=月〜7=日）を指定してください")
      _ <- Either.cond(r.frequency != "monthly" || r.monthday.exists(m => m >= 1 && m <= 31), (), "日（1〜31）を指定してください")
      _ <- Either.cond(RichText.valid(r.body) && !RichText.isBlank(r.body), (), "メッセージを入力してください")
    yield d

  /** 定期メッセージを扱う（変えられるのは作った人だけ。管理者は削除だけできる）。 */
  private def withScheduled(req: Request[IO], id: UUID, delete: Boolean = false)(f: (AuthSession, ScheduledRow) => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => store.scheduledRow(id).flatMap {
      case Some(r) if r.authorId == s.user.id || (delete && s.canAdmin) => f(s, r)
      case _ => err(Status.NotFound, "定期メッセージが見つかりません")
    })

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // ---------------------------------------------------------------- リマインダー
    case req @ GET -> Root / "api" / "reminders" =>
      authed(req)(s => store.reminders(s.user.id, param(req, "done").contains("true")).flatMap(rs =>
        // 今は見られなくなったメッセージ（非公開チャンネルから外されたなど）の抜粋は出さない
        rs.traverse(r => r.message.fold(IO.pure(r))(m => access.message(s, m.id, Need.Read).map(ok => if ok.isRight then r else r.copy(message = None))))
          .flatMap(Ok(_))))

    case req @ POST -> Root / "api" / "reminders" =>
      authed(req)(s => body[ReminderReq](req) { r =>
        val text = r.text.getOrElse("").trim.take(1000)
        if text.isEmpty && r.messageId.isEmpty then err(Status.BadRequest, "内容を入力してください")
        else if r.remindAt.isBefore(Instant.now().minusSeconds(60)) then err(Status.BadRequest, "過去の日時は指定できません")
        else
          r.messageId.traverse[IO, Either[Fail, (ChannelCtx, MessageRow)]](m => access.message(s, m, Need.Read)).flatMap {
            case Some(Left(f)) => fail(f)
            case _ => store.createReminder(s.user.id, text, r.messageId, r.remindAt) *> store.reminders(s.user.id, false).flatMap(Created(_))
          }
      })

    case req @ PATCH -> Root / "api" / "reminders" / UuidVar(id) =>
      authed(req)(s => body[ReminderPatch](req)(r => store.updateReminder(s.user.id, id, r.remindAt, r.done).flatMap(ok =>
        if ok then NoContent() else err(Status.NotFound, "リマインダーが見つかりません"))))

    case req @ DELETE -> Root / "api" / "reminders" / UuidVar(id) =>
      authed(req)(s => store.deleteReminder(s.user.id, id).flatMap(ok => if ok then NoContent() else err(Status.NotFound, "リマインダーが見つかりません")))

    // ---------------------------------------------------------------- 定期メッセージ
    case req @ GET -> Root / "api" / "scheduled" =>
      authed(req)(s => param(req, "channelId").flatMap(uuidOpt) match
        // チャンネルのメンバーだけ。ほかの人が作ったものは本文を見せない（送るまでは作った人のもの）
        case Some(c) => respond(access.channel(s, c, Need.Member))(_ => store.scheduled(None, Some(c)).flatMap(xs =>
            Ok(xs.map(x => if x.author.exists(_.id == s.user.id) then x else x.copy(body = RichText.empty, html = "")))))
        case None    => store.scheduled(Some(s.user.id), None).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "scheduled" =>
      authed(req)(s => body[ScheduledReq](req)(r => validate(s, r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(d) =>
          respond(access.channel(s, r.channelId, Need.Post)) { _ =>
            val next = svc.nextRun(r.frequency, d, r.runTime, r.weekday, r.monthday, s.user.timezone)
            if next.isEmpty then err(Status.BadRequest, "送る日時が過ぎています")
            else store.createScheduled(r, s.user.id, d, s.user.timezone, next) *> store.scheduled(Some(s.user.id), None).flatMap(Created(_))
          }))

    case req @ PUT -> Root / "api" / "scheduled" / UuidVar(id) =>
      withScheduled(req, id)((s, _) => body[ScheduledReq](req)(r => validate(s, r) match
        case Left(e) => err(Status.BadRequest, e)
        case Right(d) =>
          respond(access.channel(s, r.channelId, Need.Post)) { _ =>
            val next = svc.nextRun(r.frequency, d, r.runTime, r.weekday, r.monthday, s.user.timezone)
            if next.isEmpty && r.enabled then err(Status.BadRequest, "送る日時が過ぎています")
            else store.updateScheduled(id, r, d, s.user.timezone, next) *> NoContent()
          }))

    case req @ DELETE -> Root / "api" / "scheduled" / UuidVar(id) =>
      withScheduled(req, id, delete = true)((s, r) => store.deleteScheduled(id) *>
        activity.audit(Some(s.user.id), s.viaString, "scheduled.delete", Some(id.toString), Json.obj()).whenA(r.authorId != s.user.id) *> NoContent())
  }
