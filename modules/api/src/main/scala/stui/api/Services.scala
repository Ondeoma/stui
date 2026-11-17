package stui.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import stui.core.*
import stui.db.*
import stui.notify.{Mail, Mailer}
import stui.shared.*

import java.util.UUID
import scala.concurrent.duration.*

/** 通知（画面内・メール）。メールは少し待ってから送り、その間に画面で読んだら送らない。 */
final class NotifyService(activity: ActivityStore, cfg: ApiConfig, mailDelaySeconds: Int):

  /** 1人に通知する（ボット・無効なユーザーは呼び出し側で除く）。 */
  def send(kind: String, to: UUID, actor: Option[UUID], channelId: Option[UUID], messageId: Option[UUID], title: String, body: String,
      link: String, mail: Boolean = true): IO[Unit] =
    (for
      nid <- activity.notify(to, kind, channelId, messageId, actor, title, body.take(500))
      _ <- IO.whenA(mail) {
             val text = List(title, "", body.take(2000), "", s"${cfg.publicUrl}$link", "",
               "-- ", "stui（通知の設定は画面右上のメニュー →「個人設定」で変えられます）").mkString("\n")
             activity.enqueueMail(Some(to), None, Some(nid), s"[stui] $title", text,
               Some(s"$to:${channelId.getOrElse("")}:$kind"), mailDelaySeconds)
           }
    yield ()).handleErrorWith(e => IO.println(s"[stui] notify failed: $e"))

  /** メールの送信ワーカー（30秒ごと）。既読になった通知・止めた種類のメールは送らない。 */
  def mailLoop(mailer: Option[Mailer]): IO[Nothing] =
    Periodic.run(20.seconds, 30.seconds)(e => s"[stui] mail worker failed: $e") {
      activity.processMail(50) { row =>
        mailer match
          case None => IO.pure(OutboxResult.Skip("SMTP が設定されていません"))
          case Some(m) =>
            (row.userId, row.toAddress) match
              case (Some(uid), _) =>
                row.notificationId.fold(IO.pure(false))(activity.notificationRead).flatMap {
                  case true => IO.pure(OutboxResult.Skip("画面で既読になりました"))
                  case false =>
                    activity.mailTarget(uid, None).flatMap {
                      case None => IO.pure(OutboxResult.Skip("宛先のユーザーが無効か、メールアドレスがありません"))
                      case Some((addr, name)) => m.send(Mail(addr, name, row.subject, row.body)).as(OutboxResult.Sent)
                    }
                }
              case (None, Some(addr)) => m.send(Mail(addr, addr, row.subject, row.body)).as(OutboxResult.Sent)
              case _                  => IO.pure(OutboxResult.Skip("宛先がありません"))
      }
    }

/** 投稿の作者（人か、ボット）。via は web / api / mcp / webhook / app / reminder / schedule。 */
final case class Author(user: User, via: String)

/**
 * チャットの操作（REST・MCP・Webhook・連携サービス・定期メッセージで共通）。
 * 権限の確認・投稿・変更履歴（画面の同期）・通知・送信の Webhook まで行う。
 */
final class ChatService(channels: ChannelStore, messages: MessageStore, users: UserStore, val access: Access2, notifier: NotifyService,
    hooks: HookStore, activity: ActivityStore, cfg: ApiConfig):

  private val MaxText = 40000

  /** 投稿の本文（Tiptap の文書、または Markdown の文字列）を読む。 */
  def parseBody(j: Json): Either[Fail, Json] =
    j.asString match
      case Some(md) if md.length > MaxText => Left(Fail.Invalid(s"本文は${MaxText}文字までです"))
      case Some(md) => Right(RichText.fromMarkdown(md))
      case None if RichText.valid(j) => Right(j)
      case None => Left(Fail.Invalid("body は Tiptap の文書（{\"type\":\"doc\",…}）か Markdown の文字列にしてください"))

  def link(channelId: UUID, messageId: UUID, rootId: Option[UUID]): String =
    Paths.message(channelId, Some(messageId), rootId)

  // ---------------------------------------------------------------- 投稿

  /** 人（またはAPIキー）としての投稿。 */
  def post(s: AuthSession, channelId: UUID, r: PostMessageReq): Res[MessageDto] =
    access.channel(s, channelId, Need.Post).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) =>
        parseBody(r.body) match
          case Left(f) => IO.pure(Left(f))
          case Right(doc) =>
            publish(ctx.channel, Author(s.user, s.viaString), doc, r.rootId, r.alsoChannel.getOrElse(false), Json.obj(), "user",
              r.attachmentIds.getOrElse(Nil)).flatTap(res => IO.whenA(res.isRight && s.isSession)(messages.deleteDraft(s.user.id, channelId, r.rootId)))
    }

  /** チャンネルへの投稿の本体（権限の確認は済ませておくこと）。ボットの投稿もここを通す。 */
  def publish(c: ChannelRow, author: Author, doc: Json, rootId: Option[UUID], alsoChannel: Boolean, props: Json, kind: String,
      attachmentIds: List[UUID] = Nil): Res[MessageDto] =
    val text = RichText.toPlain(doc)
    if RichText.isBlank(doc) && attachmentIds.isEmpty then IO.pure(Left(Fail.Invalid("メッセージを入力してください")))
    else if attachmentIds.size > 20 then IO.pure(Left(Fail.Invalid("添付は20個までです")))
    else if text.length > MaxText then IO.pure(Left(Fail.Invalid(s"メッセージが長すぎます（${MaxText}文字まで）")))
    else
      rootId.traverse(messages.find).map(_.flatten).flatMap { root =>
        if rootId.isDefined && !root.exists(m => m.channelId == c.id && m.deletedAt.isEmpty) then
          IO.pure(Left(Fail.NotFound("返信するメッセージが見つかりません")))
        else
          // 返信への返信は、そのスレッドの返信にする
          val realRoot = root.map(m => m.rootId.getOrElse(m.id))
          for
            m <- messages.insert(NewMessage(c.id, realRoot, alsoChannel && realRoot.isDefined, author.user.id, kind, author.via, doc, text, props,
                   attachmentIds))
            v <- messages.view(m.id, author.user.id).map(_.get)
            _ <- fanout(c, author, m, doc, text).handleErrorWith(e => IO.println(s"[stui] fanout failed: $e"))
          yield Right(v)
      }

  /** 参加・退出などの知らせ（system）。 */
  def system(c: ChannelRow, actor: User, text: String, props: Json = Json.obj()): IO[Unit] =
    messages.insert(NewMessage(c.id, None, false, actor.id, "system", "web", RichText.fromPlain(text), text, props)).void

  /** 投稿の後: 通知・送信の Webhook。 */
  private def fanout(c: ChannelRow, author: Author, m: MessageRow, doc: Json, text: String): IO[Unit] =
    for
      rs      <- channels.recipientsIO(c.id)
      members  = rs.filterNot(r => r.isBot || r.userId == author.user.id)
      direct   = RichText.mentions(doc)
      groups   = RichText.groupMentions(doc)
      groupIds <- groups.toList.flatTraverse {
                    case "r:channel" | "r:here" => IO.pure(members.map(_.userId))
                    case g => uuidOpt(g.drop(2)).fold(IO.pure(List.empty[UUID]))(id => users.groupMembers(id))
                  }.map(_.toSet)
      mentioned = members.filter(r => direct.contains(r.userId) || (groupIds.contains(r.userId) && !r.muted)).filterNot(_.notifyLevel == NotifyLevel.None)
      followers <- m.rootId.fold(IO.pure(List.empty[UUID]))(messages.followers)
      who      = author.user.name
      snippet  = text.take(300)
      linkTo   = link(c.id, m.id, m.rootId)
      notified <- mentioned.traverse { r =>
                    notifier.send(NotificationKind.Mention, r.userId, Some(author.user.id), Some(c.id), Some(m.id),
                      s"$who さんが ${place(c)} であなたをメンションしました", snippet, linkTo).as(r.userId)
                  }.map(_.toSet)
      // DM（スレッドの返信はフォローしている人だけ）
      _ <- IO.whenA(c.isDm && m.rootId.isEmpty)(members.filterNot(r => notified(r.userId) || r.muted || r.notifyLevel == NotifyLevel.None ||
             r.notifyLevel == NotifyLevel.Mention).traverse_(r =>
               notifier.send(NotificationKind.Dm, r.userId, Some(author.user.id), Some(c.id), Some(m.id),
                 s"${author.user.name} さんからのメッセージ", snippet, linkTo)))
      // スレッドの返信（フォローしている人）
      memberIds = members.map(r => r.userId -> r).toMap
      threadTo  = followers.filter(u => u != author.user.id && !notified(u) && memberIds.get(u).exists(r => r.notifyLevel != NotifyLevel.None))
      _ <- m.rootId.traverse_(_ => threadTo.traverse_(u =>
             notifier.send(NotificationKind.Thread, u, Some(author.user.id), Some(c.id), Some(m.id), s"${place(c)} のスレッドに $who さんが返信しました", snippet,
               linkTo, mail = c.isDm)))
      // 「すべてのメッセージ」を選んだチャンネル
      _ <- IO.whenA(!c.isDm && m.rootId.isEmpty)(members.filter(r => r.notifyLevel == NotifyLevel.All && !r.muted && !notified(r.userId))
             .traverse_(r => notifier.send(NotificationKind.Channel, r.userId, Some(author.user.id), Some(c.id), Some(m.id),
               s"${place(c)} に $who さんが投稿しました", snippet, linkTo, mail = false)))
      // スレッドでメンションされた人はフォローにする
      _ <- m.rootId.traverse_(r => mentioned.traverse_(x => messages.setFollow(r, x.userId, on = true)))
      _ <- IO.whenA(m.kind != "bot")(webhook(c, EventKind.MessageCreated, Some(m), Some(author.user), Some(text)))
    yield ()

  private def place(c: ChannelRow): String = if c.isDm then "DM" else s"#${c.name}"

  /** 送信の Webhook に積む。 */
  def webhook(c: ChannelRow, event: String, m: Option[MessageRow], actor: Option[User], text: Option[String], extra: Json = Json.obj()): IO[Unit] =
    val payload = Json.obj(
      "event"   -> event.asJson,
      "channel" -> Json.obj("id" -> c.id.asJson, "name" -> c.name.asJson, "kind" -> c.kind.asJson),
      "actor"   -> actor.fold(Json.Null)(u => Json.obj("id" -> u.id.asJson, "name" -> u.name.asJson, "bot" -> u.isBot.asJson)),
      "message" -> m.fold(Json.Null)(x => Json.obj(
        "id" -> x.id.asJson, "rootId" -> x.rootId.asJson, "text" -> x.bodyText.asJson, "createdAt" -> x.createdAt.asJson,
        "url" -> s"${cfg.publicUrl}${link(c.id, x.id, x.rootId)}".asJson)),
      "at" -> java.time.Instant.now().asJson
    ).deepMerge(extra)
    hooks.enqueue(c.id, c.isPublic, event, payload, text).void.handleErrorWith(e => IO.println(s"[stui] webhook enqueue failed: $e"))

  // ---------------------------------------------------------------- 編集・削除・リアクション・ピン留め

  def edit(s: AuthSession, id: UUID, body: Json): Res[MessageDto] =
    access.message(s, id, Need.Post).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right((_, m)) if !m.authorId.contains(s.user.id) => IO.pure(Left(Fail.Forbidden("自分のメッセージだけを編集できます")))
      case Right((_, m)) if m.deletedAt.isDefined || m.kind == "system" => IO.pure(Left(Fail.Invalid("このメッセージは編集できません")))
      case Right((ctx, m)) =>
        parseBody(body) match
          case Left(f) => IO.pure(Left(f))
          case Right(doc) if RichText.isBlank(doc) => IO.pure(Left(Fail.Invalid("メッセージを入力してください（消すときは削除してください）")))
          case Right(doc) =>
            val text = RichText.toPlain(doc)
            messages.edit(id, doc, text, s.user.id) *>
              webhook(ctx.channel, EventKind.MessageUpdated, Some(m.copy(bodyText = text)), Some(s.user), None) *>
              messages.view(id, s.user.id).map(v => Right(v.get))
    }

  def delete(s: AuthSession, id: UUID): Res[Unit] =
    access.message(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right((ctx, m)) =>
        val own = m.authorId.contains(s.user.id)
        if !s.canWrite then IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
        else if !own && !ctx.isChannelAdmin && !s.canAdmin then IO.pure(Left(Fail.Forbidden("自分のメッセージだけを削除できます")))
        else
          messages.delete(id, s.user.id) *> webhook(ctx.channel, EventKind.MessageDeleted, Some(m), Some(s.user), None) *>
            activity.audit(Some(s.user.id), s.viaString, "message.delete", Some(id.toString), Json.obj("own" -> own.asJson)).whenA(!own)
              .as(Right(()))
    }

  def react(s: AuthSession, id: UUID, emoji: String, on: Boolean): Res[List[ReactionDto]] =
    if on && !Reactions.valid(emoji) then IO.pure(Left(Fail.Invalid("使えない絵文字です")))
    else
      access.message(s, id, Need.Member).flatMap {
        case Left(f) => IO.pure(Left(f))
        case Right(_) if !s.canWrite => IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
        case Right((ctx, _)) if ctx.channel.archived => IO.pure(Left(Fail.Forbidden("アーカイブしたチャンネルです")))
        case Right((_, m)) if m.deletedAt.isDefined => IO.pure(Left(Fail.Invalid("削除したメッセージです")))
        case Right((ctx, m)) =>
          messages.react(id, s.user.id, emoji, on) *>
            webhook(ctx.channel, EventKind.ReactionChanged, Some(m), Some(s.user), None, Json.obj("emoji" -> emoji.asJson, "added" -> on.asJson)) *>
            messages.reactions(id).map(Right(_))
      }

  def pin(s: AuthSession, id: UUID, on: Boolean): Res[Unit] =
    access.message(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) if !s.canWrite => IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
      case Right((ctx, _)) if ctx.channel.archived => IO.pure(Left(Fail.Forbidden("アーカイブしたチャンネルです")))
      case Right((_, m)) if m.deletedAt.isDefined || m.kind == "system" => IO.pure(Left(Fail.Invalid("このメッセージはピン留めできません")))
      case Right((ctx, m)) =>
        messages.pin(id, s.user.id, on) *>
          IO.whenA(on)(system(ctx.channel, s.user, s"${s.user.name} さんがメッセージをピン留めしました", Json.obj("pinned" -> m.id.asJson)))
            .as(Right(()))
    }

  // ---------------------------------------------------------------- チャンネル

  def createChannel(s: AuthSession, r: CreateChannelReq): Res[ChannelDto] =
    ChannelName.normalize(r.name) match
      case Left(m) => IO.pure(Left(Fail.Invalid(m)))
      case Right(_) if !Set(ChannelKind.Public, ChannelKind.Private).contains(r.kind) => IO.pure(Left(Fail.Invalid("kind は public か private です")))
      case Right(_) if !s.canWrite => IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
      case Right(_) if r.memberIds.size > 500 => IO.pure(Left(Fail.Invalid("最初のメンバーは500人までです")))
      case Right(name) =>
        channels.create(name, r.kind, r.topic.getOrElse("").trim.take(250), r.purpose.getOrElse("").trim.take(250), s.user.id, r.memberIds).orFail
          .flatMap {
            case Left(f) => IO.pure(Left(f))
            case Right(c) =>
              messages.eventIO(c.id, EventKind.ChannelCreated, actor = Some(s.user.id)) *>
                webhook(c, EventKind.ChannelCreated, None, Some(s.user), None) *>
                activity.audit(Some(s.user.id), s.viaString, "channel.create", Some(c.id.toString), Json.obj("name" -> name.asJson, "kind" -> r.kind.asJson)) *>
                channels.get(c.id, s.user.id).map(v => Right(v.get))
          }

  def updateChannel(s: AuthSession, id: UUID, r: UpdateChannelReq): Res[ChannelDto] =
    access.channel(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) =>
        // トピック・説明はメンバーなら誰でも変えられる。名前・アーカイブ・自動参加は管理者だけ
        val manage = r.name.isDefined || r.archived.isDefined || r.autoJoin.isDefined
        val allowed =
          if !s.canWrite then Left(Fail.Forbidden("このAPIキーは参照専用です"))
          else if ctx.channel.isDm && manage then Left(Fail.Forbidden("DM の名前は変えられません"))
          else if manage && !ctx.isChannelAdmin && !s.canAdmin then Left(Fail.Forbidden("チャンネルの管理者だけが変えられます"))
          else if r.autoJoin.contains(true) && !s.canAdmin then Left(Fail.Forbidden("自動参加はシステム管理者だけが設定できます"))
          else if !ctx.isMember && !s.canAdmin then Left(Fail.Forbidden("チャンネルに参加してください"))
          else if ctx.channel.archived && !r.archived.contains(false) && (r.topic.isDefined || r.purpose.isDefined) then
            Left(Fail.Forbidden("アーカイブしたチャンネルです（戻してから変えてください）"))
          else Right(())
        allowed match
          case Left(f) => IO.pure(Left(f))
          case Right(_) =>
            r.name.traverse(ChannelName.normalize) match
              case Left(m) => IO.pure(Left(Fail.Invalid(m)))
              case Right(name) =>
                channels.update(id, r, name).orFail.flatMap {
                  case Left(f) => IO.pure(Left(f))
                  case Right(_) =>
                    val c = ctx.channel
                    val notes = List(
                      r.topic.filter(_.trim != c.topic).map(t => if t.trim.isEmpty then s"${s.user.name} さんがトピックを消しました" else s"${s.user.name} さんがトピックを「${t.trim}」にしました"),
                      name.filter(_ != c.name).map(n => s"${s.user.name} さんがチャンネルの名前を「$n」にしました"),
                      r.archived.filter(_ != c.archived).map(a => if a then s"${s.user.name} さんがチャンネルをアーカイブしました" else s"${s.user.name} さんがアーカイブを戻しました")
                    ).flatten
                    notes.traverse_(t => system(c, s.user, t)) *>
                      messages.eventIO(id, EventKind.ChannelUpdated, actor = Some(s.user.id)) *>
                      channels.get(id, s.user.id).map(v => Right(v.get))
                }
    }

  def join(s: AuthSession, id: UUID): Res[ChannelDto] =
    access.channel(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.isMember => channels.get(id, s.user.id).map(v => Right(v.get))
      case Right(ctx) if ctx.channel.archived => IO.pure(Left(Fail.Forbidden("アーカイブしたチャンネルには参加できません")))
      case Right(ctx) =>
        channels.addMembers(id, List(s.user.id)) *> memberJoined(ctx.channel, s.user, List(s.user.id)) *>
          channels.get(id, s.user.id).map(v => Right(v.get))
    }

  def addMembers(s: AuthSession, id: UUID, userIds: List[UUID]): Res[List[UUID]] =
    access.channel(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.channel.isDm => IO.pure(Left(Fail.Invalid("DM には追加できません。新しいグループ DM を作ってください")))
      case Right(ctx) if ctx.channel.archived => IO.pure(Left(Fail.Forbidden("アーカイブしたチャンネルです")))
      case Right(_) if !s.canWrite => IO.pure(Left(Fail.Forbidden("このAPIキーは参照専用です")))
      case Right(_) if userIds.size > 500 => IO.pure(Left(Fail.Invalid("一度に追加できるのは500人までです")))
      case Right(ctx) =>
        channels.addMembers(id, userIds).flatTap(added => memberJoined(ctx.channel, s.user, added)).map(Right(_))
    }

  private def memberJoined(c: ChannelRow, actor: User, added: List[UUID]): IO[Unit] =
    IO.whenA(added.nonEmpty)(
      users.briefsIO(added).flatMap { bs =>
        val names = bs.map(_.name).mkString("、")
        val text  = if added == List(actor.id) then s"${actor.name} さんが参加しました" else s"${actor.name} さんが $names さんを追加しました"
        system(c, actor, text) *> added.traverse_(u =>
          messages.eventIO(c.id, EventKind.MemberJoined, actor = Some(actor.id), user = Some(u)) *>
            webhook(c, EventKind.MemberJoined, None, Some(actor), None, Json.obj("user" -> u.asJson)))
      })

  def removeMember(s: AuthSession, id: UUID, userId: UUID): Res[Unit] =
    access.channel(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.channel.isDm => IO.pure(Left(Fail.Invalid("DM からは抜けられません（一覧から隠せます）")))
      case Right(ctx) if userId != s.user.id && !ctx.isChannelAdmin && !s.canAdmin => IO.pure(Left(Fail.Forbidden("チャンネルの管理者だけが外せます")))
      case Right(ctx) =>
        channels.removeMember(id, userId).flatMap {
          case false => IO.pure(Left(Fail.NotFound("メンバーではありません")))
          case true =>
            users.find(userId).flatMap { u =>
              val name = u.map(_.name).getOrElse("")
              system(ctx.channel, s.user, if userId == s.user.id then s"${s.user.name} さんが退出しました" else s"${s.user.name} さんが $name さんを外しました") *>
                messages.eventIO(id, EventKind.MemberLeft, actor = Some(s.user.id), user = Some(userId)) *>
                webhook(ctx.channel, EventKind.MemberLeft, None, Some(s.user), None, Json.obj("user" -> userId.asJson)).as(Right(()))
            }
        }
    }

  /** DM を開く（自分と相手。相手が空なら自分だけの DM）。 */
  def openDm(s: AuthSession, userIds: List[UUID]): Res[ChannelDto] =
    val others = userIds.distinct.filterNot(_ == s.user.id)
    if others.size > 8 then IO.pure(Left(Fail.Invalid("グループ DM は9人までです")))
    else
      channels.activeUsers(others).flatMap { found =>
        if found.size != others.size then IO.pure(Left(Fail.NotFound("ユーザーが見つかりません")))
        else channels.openDm(s.user.id :: others).flatMap(c => channels.get(c.id, s.user.id).map(v => Right(v.get)))
      }
