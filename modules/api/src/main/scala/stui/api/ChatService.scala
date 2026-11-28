package stui.api

import sbirds.shared.AppError

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import stui.core.*
import stui.db.*
import stui.shared.*

import java.util.UUID

/**
 * チャットの操作（REST・MCP・Webhook・連携サービス・定期メッセージで共通）。
 * 権限の確認・投稿・変更履歴（画面の同期）・通知・送信の Webhook まで行う。
 */
final class ChatService(channels: ChannelStore,
                        messages: MessageStore,
                        users: UserStore,
                        val access: Access2,
                        notifier: NotifyService,
                        hooks: HookStore,
                        activity: ActivityStore,
                        cfg: ApiConfig) {

  private val MaxText = 40000

  /** 投稿の本文（Tiptap の文書、または Markdown の文字列）を読む。 */
  def parseBody(j: Json): Either[AppError, Json] =
    j.asString match {
      case Some(md) if md.length > MaxText => Left(AppError(ErrorCode.MessageTooLong, s"本文は${MaxText}文字までです"))
      case Some(md) => Right(RichText.fromMarkdown(md))
      case None if RichText.valid(j) => Right(j)
      case None => Left(AppError(ErrorCode.MessageBodyFormatInvalid))
    }

  def link(channelId: UUID, messageId: UUID, rootId: Option[UUID]): String =
    Paths.message(channelId, Some(messageId), rootId)

  // ---------------------------------------------------------------- 投稿

  /** 人（またはAPIキー）としての投稿。 */
  def post(s: AuthSession, channelId: UUID, r: PostMessageReq): Res[MessageDto] =
    sbirds.observe.Telemetry.span(
      "chat.post",
      "channel.id" -> channelId.toString,
      "kind" -> (if r.rootId.isDefined then "reply" else "message")
    )(postImpl(s, channelId, r)).flatTap(res =>
      IO.whenA(res.isRight)(sbirds.observe.Metrics.count(
        "stui.messages.posted",
        "kind" -> (if r.rootId.isDefined then "reply" else "message"),
        "via" -> (if s.isSession then "ui" else "api")
      ))
    )

  private def postImpl(s: AuthSession, channelId: UUID, r: PostMessageReq): Res[MessageDto] =
    access.channel(s, channelId, Need.Post).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) =>
        parseBody(r.body) match {
          case Left(f) => IO.pure(Left(f))
          case Right(doc) =>
            publish(
              ctx.channel,
              Author(s.user, s.viaString),
              doc,
              r.rootId,
              r.alsoChannel.getOrElse(false),
              Json.obj(),
              "user",
              r.attachmentIds.getOrElse(Nil)
            ).flatTap(res => IO.whenA(res.isRight && s.isSession)(messages.deleteDraft(s.user.id, channelId, r.rootId)))
        }
    }

  /** チャンネルへの投稿の本体（権限の確認は済ませておくこと）。ボットの投稿もここを通す。 */
  def publish(c: ChannelRow,
              author: Author,
              doc: Json,
              rootId: Option[UUID],
              alsoChannel: Boolean,
              props: Json,
              kind: String,
              attachmentIds: List[UUID] = Nil): Res[MessageDto] = {
    val text = RichText.toPlain(doc)
    if RichText.isBlank(doc) && attachmentIds.isEmpty then IO.pure(Left(AppError(ErrorCode.MessageTextRequired)))
    else if attachmentIds.size > 20 then IO.pure(Left(AppError(ErrorCode.MessageTooManyAttachments)))
    else if text.length > MaxText then IO.pure(Left(AppError(ErrorCode.MessageTooLong, s"メッセージが長すぎます（${MaxText}文字まで）")))
    else
      rootId.traverse(messages.find).map(_.flatten).flatMap { root =>
        if rootId.isDefined && !root.exists(m => m.channelId == c.id && m.deletedAt.isEmpty) then
          IO.pure(Left(AppError(ErrorCode.ReplyTargetNotFound)))
        else {
          // 返信への返信は、そのスレッドの返信にする
          val realRoot = root.map(m => m.rootId.getOrElse(m.id))
          for {
            m <- messages.insert(NewMessage(
              c.id,
              realRoot,
              alsoChannel && realRoot.isDefined,
              author.user.id,
              kind,
              author.via,
              doc,
              text,
              props,
              attachmentIds
            ))
            v <- messages.view(m.id, author.user.id).map(_.get)
            _ <- sbirds.observe.Telemetry.span("chat.fanout")(fanout(c, author, m, doc, text)).handleErrorWith(e =>
              sbirds.observe.Log.report(ErrorCode.FanoutFailed, e.toString, e)
            )
          } yield Right(v)
        }
      }
  }

  /** 参加・退出などの知らせ（system）。 */
  def system(c: ChannelRow, actor: User, text: String, props: Json = Json.obj()): IO[Unit] =
    messages.insert(NewMessage(
      c.id,
      None,
      false,
      actor.id,
      "system",
      "web",
      RichText.fromPlain(text),
      text,
      props
    )).void

  /** 投稿の後: 通知・送信の Webhook。 */
  private def fanout(c: ChannelRow, author: Author, m: MessageRow, doc: Json, text: String): IO[Unit] =
    for {
      rs <- channels.recipientsIO(c.id)
      members = rs.filterNot(r => r.isBot || r.userId == author.user.id)
      direct = RichText.mentions(doc)
      groups = RichText.groupMentions(doc)
      groupIds <- groups.toList.flatTraverse {
        case "r:channel" | "r:here" => IO.pure(members.map(_.userId))
        case g => uuidOpt(g.drop(2)).fold(IO.pure(List.empty[UUID]))(id => users.groupMembers(id))
      }.map(_.toSet)
      mentioned = members.filter(r => direct.contains(r.userId) || (groupIds.contains(r.userId) && !r.muted)).filterNot(
        _.notifyLevel == NotifyLevel.None
      )
      followers <- m.rootId.fold(IO.pure(List.empty[UUID]))(messages.followers)
      who = author.user.name
      snippet = text.take(300)
      linkTo = link(c.id, m.id, m.rootId)
      notified <- mentioned.traverse { r =>
        notifier.send(
          NotificationKind.Mention,
          r.userId,
          Some(author.user.id),
          Some(c.id),
          Some(m.id),
          s"$who さんが ${place(c)} であなたをメンションしました",
          snippet,
          linkTo
        ).as(r.userId)
      }.map(_.toSet)
      // DM（スレッドの返信はフォローしている人だけ）
      _ <- IO.whenA(c.isDm && m.rootId.isEmpty)(members.filterNot(r =>
        notified(r.userId) || r.muted || r.notifyLevel == NotifyLevel.None ||
          r.notifyLevel == NotifyLevel.Mention
      ).traverse_(r =>
        notifier.send(
          NotificationKind.Dm,
          r.userId,
          Some(author.user.id),
          Some(c.id),
          Some(m.id),
          s"${author.user.name} さんからのメッセージ",
          snippet,
          linkTo
        )
      ))
      // スレッドの返信（フォローしている人）
      memberIds = members.map(r => r.userId -> r).toMap
      threadTo = followers.filter(u =>
        u != author.user.id && !notified(u) && memberIds.get(u).exists(r => r.notifyLevel != NotifyLevel.None)
      )
      _ <- m.rootId.traverse_(_ =>
        threadTo.traverse_(u =>
          notifier.send(
            NotificationKind.Thread,
            u,
            Some(author.user.id),
            Some(c.id),
            Some(m.id),
            s"${place(c)} のスレッドに $who さんが返信しました",
            snippet,
            linkTo,
            mail = c.isDm
          )
        )
      )
      // 「すべてのメッセージ」を選んだチャンネル
      _ <- IO.whenA(!c.isDm && m.rootId.isEmpty)(members.filter(r =>
        r.notifyLevel == NotifyLevel.All && !r.muted && !notified(r.userId)
      )
        .traverse_(r =>
          notifier.send(
            NotificationKind.Channel,
            r.userId,
            Some(author.user.id),
            Some(c.id),
            Some(m.id),
            s"${place(c)} に $who さんが投稿しました",
            snippet,
            linkTo,
            mail = false
          )
        ))
      // スレッドでメンションされた人はフォローにする
      _ <- m.rootId.traverse_(r => mentioned.traverse_(x => messages.setFollow(r, x.userId, on = true)))
      _ <- IO.whenA(m.kind != "bot")(webhook(c, EventKind.MessageCreated, Some(m), Some(author.user), Some(text)))
    } yield ()

  private def place(c: ChannelRow): String = if c.isDm then "DM" else s"#${c.name}"

  /** 送信の Webhook に積む。 */
  def webhook(c: ChannelRow,
              event: String,
              m: Option[MessageRow],
              actor: Option[User],
              text: Option[String],
              extra: Json = Json.obj()): IO[Unit] = {
    val payload = Json.obj(
      "event" -> event.asJson,
      "channel" -> Json.obj("id" -> c.id.asJson, "name" -> c.name.asJson, "kind" -> c.kind.asJson),
      "actor" ->
        actor.fold(Json.Null)(u => Json.obj("id" -> u.id.asJson, "name" -> u.name.asJson, "bot" -> u.isBot.asJson)),
      "message" -> m.fold(Json.Null)(x =>
        Json.obj(
          "id" -> x.id.asJson,
          "rootId" -> x.rootId.asJson,
          "text" -> x.bodyText.asJson,
          "createdAt" -> x.createdAt.asJson,
          "url" -> s"${cfg.publicUrl}${link(c.id, x.id, x.rootId)}".asJson
        )
      ),
      "at" -> java.time.Instant.now().asJson
    ).deepMerge(extra)
    hooks.enqueue(c.id, c.isPublic, event, payload, text).void.handleErrorWith(e =>
      sbirds.observe.Log.report(ErrorCode.WebhookEnqueueFailed, e.toString, e)
    )
  }

  // ---------------------------------------------------------------- 編集・削除・リアクション・ピン留め

  def edit(s: AuthSession, id: UUID, body: Json): Res[MessageDto] =
    access.message(s, id, Need.Post).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right((_, m)) if !m.authorId.contains(s.user.id) => IO.pure(Left(AppError(ErrorCode.MessageEditNotOwner)))
      case Right((_, m)) if m.deletedAt.isDefined || m.kind == "system" =>
        IO.pure(Left(AppError(ErrorCode.MessageNotEditable)))
      case Right((ctx, m)) =>
        parseBody(body) match {
          case Left(f) => IO.pure(Left(f))
          case Right(doc) if RichText.isBlank(doc) =>
            IO.pure(Left(AppError(ErrorCode.MessageTextRequired, "メッセージを入力してください（消すときは削除してください）")))
          case Right(doc) =>
            val text = RichText.toPlain(doc)
            messages.edit(id, doc, text, s.user.id) *>
              webhook(ctx.channel, EventKind.MessageUpdated, Some(m.copy(bodyText = text)), Some(s.user), None) *>
              messages.view(id, s.user.id).map(v => Right(v.get))
        }
    }

  def delete(s: AuthSession, id: UUID): Res[Unit] =
    access.message(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right((ctx, m)) =>
        val own = m.authorId.contains(s.user.id)
        if !s.canWrite then IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
        else if !own && !ctx.isChannelAdmin && !s.canAdmin then IO.pure(Left(AppError(ErrorCode.MessageDeleteNotOwner)))
        else
          messages.delete(id, s.user.id) *>
            webhook(ctx.channel, EventKind.MessageDeleted, Some(m), Some(s.user), None) *>
            activity.audit(
              Some(s.user.id),
              s.viaString,
              "message.delete",
              Some(id.toString),
              Json.obj("own" -> own.asJson)
            ).whenA(!own)
              .as(Right(()))
    }

  def react(s: AuthSession, id: UUID, emoji: String, on: Boolean): Res[List[ReactionDto]] =
    if on && !Reactions.valid(emoji) then IO.pure(Left(AppError(ErrorCode.ReactionEmojiInvalid)))
    else
      access.message(s, id, Need.Member).flatMap {
        case Left(f) => IO.pure(Left(f))
        case Right(_) if !s.canWrite => IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
        case Right((ctx, _)) if ctx.channel.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchived)))
        case Right((_, m)) if m.deletedAt.isDefined => IO.pure(Left(AppError(ErrorCode.MessageDeleted)))
        case Right((ctx, m)) =>
          messages.react(id, s.user.id, emoji, on) *>
            webhook(
              ctx.channel,
              EventKind.ReactionChanged,
              Some(m),
              Some(s.user),
              None,
              Json.obj("emoji" -> emoji.asJson, "added" -> on.asJson)
            ) *>
            messages.reactions(id).map(Right(_))
      }

  def pin(s: AuthSession, id: UUID, on: Boolean): Res[Unit] =
    access.message(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) if !s.canWrite => IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
      case Right((ctx, _)) if ctx.channel.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchived)))
      case Right((_, m)) if m.deletedAt.isDefined || m.kind == "system" =>
        IO.pure(Left(AppError(ErrorCode.MessageNotPinnable)))
      case Right((ctx, m)) =>
        messages.pin(id, s.user.id, on) *>
          IO.whenA(on)(system(
            ctx.channel,
            s.user,
            s"${s.user.name} さんがメッセージをピン留めしました",
            Json.obj("pinned" -> m.id.asJson)
          ))
            .as(Right(()))
    }

  // ---------------------------------------------------------------- チャンネル

  def createChannel(s: AuthSession, r: CreateChannelReq): Res[ChannelDto] =
    ChannelName.normalize(r.name) match {
      case Left(m) => IO.pure(Left(AppError(ErrorCode.ChannelNameInvalid, m)))
      case Right(_) if !Set(ChannelKind.Public, ChannelKind.Private).contains(r.kind) =>
        IO.pure(Left(AppError(ErrorCode.ChannelKindInvalid)))
      case Right(_) if !s.canWrite => IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
      case Right(_) if r.memberIds.size > 500 => IO.pure(Left(AppError(ErrorCode.ChannelTooManyInitialMembers)))
      case Right(name) =>
        channels.create(
          name,
          r.kind,
          r.topic.getOrElse("").trim.take(250),
          r.purpose.getOrElse("").trim.take(250),
          s.user.id,
          r.memberIds,
          voice = r.voice.contains(true)
        )
          .flatMap {
            case Left(f) => IO.pure(Left(f))
            case Right(c) =>
              messages.eventIO(c.id, EventKind.ChannelCreated, actor = Some(s.user.id)) *>
                webhook(c, EventKind.ChannelCreated, None, Some(s.user), None) *>
                activity.audit(
                  Some(s.user.id),
                  s.viaString,
                  "channel.create",
                  Some(c.id.toString),
                  Json.obj("name" -> name.asJson, "kind" -> r.kind.asJson)
                ) *>
                channels.get(c.id, s.user.id).map(v => Right(v.get))
          }
    }

  def updateChannel(s: AuthSession, id: UUID, r: UpdateChannelReq): Res[ChannelDto] =
    access.channel(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) =>
        // トピック・説明はメンバーなら誰でも変えられる。名前・アーカイブ・自動参加・ボイスチャンネルかどうかは管理者だけ
        val manage = r.name.isDefined || r.archived.isDefined || r.autoJoin.isDefined || r.voice.isDefined
        val allowed =
          if !s.canWrite then Left(AppError(ErrorCode.ApiKeyReadOnly))
          else if ctx.channel.isDm && r.voice.isDefined then Left(AppError(ErrorCode.VoiceDmNotAllowed))
          else if ctx.channel.isDm && manage then Left(AppError(ErrorCode.DmRenameNotAllowed))
          else if manage && !ctx.isChannelAdmin && !s.canAdmin then Left(AppError(ErrorCode.ChannelAdminOnly))
          else if r.autoJoin.contains(true) && !s.canAdmin then Left(AppError(ErrorCode.ChannelAutoJoinAdminOnly))
          else if !ctx.isMember && !s.canAdmin then Left(AppError(ErrorCode.ChannelJoinRequired))
          else if ctx.channel.archived && !r.archived.contains(false) && (r.topic.isDefined || r.purpose.isDefined) then
            Left(AppError(ErrorCode.ChannelArchived, "アーカイブしたチャンネルです（戻してから変えてください）"))
          else Right(())
        allowed match {
          case Left(f) => IO.pure(Left(f))
          case Right(_) =>
            r.name.traverse(ChannelName.normalize) match {
              case Left(m) => IO.pure(Left(AppError(ErrorCode.ChannelNameInvalid, m)))
              case Right(name) =>
                channels.update(id, r, name).flatMap {
                  case Left(f) => IO.pure(Left(f))
                  case Right(_) =>
                    val c = ctx.channel
                    val notes = List(
                      r.topic.filter(_.trim != c.topic).map(t =>
                        if t.trim.isEmpty then s"${s.user.name} さんがトピックを消しました"
                        else s"${s.user.name} さんがトピックを「${t.trim}」にしました"
                      ),
                      name.filter(_ != c.name).map(n => s"${s.user.name} さんがチャンネルの名前を「$n」にしました"),
                      r.archived.filter(_ != c.archived).map(a =>
                        if a then s"${s.user.name} さんがチャンネルをアーカイブしました" else s"${s.user.name} さんがアーカイブを戻しました"
                      ),
                      r.voice.filter(_ != c.voice).map(v =>
                        if v then s"${s.user.name} さんがボイスチャンネルにしました" else s"${s.user.name} さんがボイスチャンネルをやめました"
                      )
                    ).flatten
                    notes.traverse_(t => system(c, s.user, t)) *>
                      messages.eventIO(id, EventKind.ChannelUpdated, actor = Some(s.user.id)) *>
                      channels.get(id, s.user.id).map(v => Right(v.get))
                }
            }
        }
    }

  def join(s: AuthSession, id: UUID): Res[ChannelDto] =
    access.channel(s, id, Need.Read).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.isMember => channels.get(id, s.user.id).map(v => Right(v.get))
      case Right(ctx) if ctx.channel.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchivedJoin)))
      case Right(ctx) =>
        channels.addMembers(id, List(s.user.id), Some(s.user.id)) *>
          memberJoined(ctx.channel, s.user, List(s.user.id)) *>
          channels.get(id, s.user.id).map(v => Right(v.get))
    }

  def addMembers(s: AuthSession, id: UUID, userIds: List[UUID]): Res[List[UUID]] =
    access.channel(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.channel.isDm => IO.pure(Left(AppError(ErrorCode.DmAddMemberNotAllowed)))
      case Right(ctx) if ctx.channel.archived => IO.pure(Left(AppError(ErrorCode.ChannelArchived)))
      case Right(_) if !s.canWrite => IO.pure(Left(AppError(ErrorCode.ApiKeyReadOnly)))
      case Right(_) if userIds.size > 500 => IO.pure(Left(AppError(ErrorCode.ChannelTooManyMembersAdded)))
      case Right(ctx) => addMembersTo(ctx.channel, s.user, userIds).map(Right(_))
    }

  /** 権限を確かめた後に、メンバーを追加して知らせる（管理画面からエージェント専用アカウントを参加させるときも使う）。 */
  def addMembersTo(c: ChannelRow, actor: User, userIds: List[UUID]): IO[List[UUID]] =
    channels.addMembers(c.id, userIds, Some(actor.id)).flatTap(added => memberJoined(c, actor, added))

  private def memberJoined(c: ChannelRow, actor: User, added: List[UUID]): IO[Unit] =
    IO.whenA(added.nonEmpty)(
      users.briefsIO(added).flatMap { bs =>
        val names = bs.map(_.name).mkString("、")
        val text = if added == List(actor.id) then s"${actor.name} さんが参加しました" else s"${actor.name} さんが $names さんを追加しました"
        // 変更履歴（member.joined）は参加させるときに ChannelStore が残す
        system(c, actor, text) *> added.traverse_(u =>
          webhook(c, EventKind.MemberJoined, None, Some(actor), None, Json.obj("user" -> u.asJson))
        )
      }
    )

  def removeMember(s: AuthSession, id: UUID, userId: UUID): Res[Unit] =
    access.channel(s, id, Need.Member).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(ctx) if ctx.channel.isDm => IO.pure(Left(AppError(ErrorCode.DmLeaveNotAllowed)))
      case Right(ctx) if userId != s.user.id && !ctx.isChannelAdmin && !s.canAdmin =>
        IO.pure(Left(AppError(ErrorCode.ChannelAdminOnly, "チャンネルの管理者だけが外せます")))
      case Right(ctx) => removeMemberFrom(ctx.channel, s.user, userId)
    }

  /** 権限を確かめた後に、メンバーを外して知らせる（管理画面からエージェント専用アカウントを外すときも使う）。 */
  def removeMemberFrom(c: ChannelRow, actor: User, userId: UUID): Res[Unit] =
    channels.removeMember(c.id, userId).flatMap {
      case false => IO.pure(Left(AppError(ErrorCode.NotChannelMember)))
      case true =>
        users.find(userId).flatMap { u =>
          val name = u.map(_.name).getOrElse("")
          system(
            c,
            actor,
            if userId == actor.id then s"${actor.name} さんが退出しました" else s"${actor.name} さんが $name さんを外しました"
          ) *>
            messages.eventIO(c.id, EventKind.MemberLeft, actor = Some(actor.id), user = Some(userId)) *>
            webhook(c, EventKind.MemberLeft, None, Some(actor), None, Json.obj("user" -> userId.asJson)).as(Right(()))
        }
    }

  /** DM を開く（自分と相手。相手が空なら自分だけの DM）。 */
  def openDm(s: AuthSession, userIds: List[UUID]): Res[ChannelDto] = {
    val others = userIds.distinct.filterNot(_ == s.user.id)
    if others.size > 8 then IO.pure(Left(AppError(ErrorCode.GroupDmTooManyMembers)))
    else
      channels.activeUsers(others).flatMap { found =>
        if found.size != others.size then IO.pure(Left(AppError(ErrorCode.UserNotFound)))
        else channels.openDm(s.user.id :: others).flatMap(c => channels.get(c.id, s.user.id).map(v => Right(v.get)))
      }
  }
}
