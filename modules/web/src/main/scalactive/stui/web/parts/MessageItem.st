<script lang="scala">
import stui.web.*
import org.scalajs.dom
import stui.shared.*

import java.util.UUID

// メッセージ1件（本文・添付・リアクション・スレッドの返信の数・操作のメニュー・その場での編集）.
// compact は同じ人の続けての投稿（アイコンと名前を省く）.
case class Props(m: MessageDto, compact: Boolean = false, inThread: Boolean = false, canPost: Boolean = true, canManage: Boolean = false,
                 highlight: Boolean = false, editing: Boolean = false,
                 onEdit: Option[UUID] => Unit = _ => (), onReply: MessageDto => Unit = _ => (), onChanged: MessageDto => Unit = _ => (),
                 onRemoved: UUID => Unit = _ => ())

case class State(editor: RV[Option[EditorHandle]] = RV(None), picker: RV[Boolean] = RV(false), menu: RV[Boolean] = RV(false), up: RV[Boolean] = RV(false),
                 remind: RV[Boolean] = RV(false))

def initState() = State()

def m(props: Props): MessageDto = props.m

def mine(props: Props): Boolean = props.m.author.exists(a => Store.myId.contains(a.id))

/** 表示名（Webhook は送ってきた名前）. */
def name(props: Props): String =
  props.m.props.hcursor.get[String]("username").toOption.orElse(props.m.author.map(_.name)).getOrElse("（削除されたユーザー）")

def iconUrl(props: Props): Option[String] = props.m.props.hcursor.get[String]("iconUrl").toOption
def iconEmoji(props: Props): Option[String] = props.m.props.hcursor.get[String]("iconEmoji").toOption.filter(_ => iconUrl(props).isEmpty)
/** カスタム絵文字のアイコン（:name: で、登録されているもの）の画像. */
def iconImage(props: Props): Option[String] = iconEmoji(props).filter(e => Emojis.custom.v.exists(c => Reactions.customName(e).contains(c.name))).flatMap(Emojis.image)
/** 文字の絵文字のアイコン（:name: は絵文字の文字にする。描けないものは出さず、ふつうのアイコンにする）. */
def iconChar(props: Props): Option[String] = iconEmoji(props).map(HookIcons.normalize).filter(HookIcons.drawable)
def isBot(props: Props): Boolean = props.m.kind == "bot" || props.m.author.exists(_.bot)
def isSystem(props: Props): Boolean = props.m.kind == "system"

/** 会議の案内（skaka）. */
def meeting(props: Props): Option[(String, String)] = {
  val c = props.m.props.hcursor.downField("meeting")
  // 会議の URL は http(s) だけ（javascript: などは出さない）
  (c.get[String]("url").toOption.filter(u => u.startsWith("https://") || u.startsWith("http://")), c.get[String]("name").toOption) match {
    case (Some(u), Some(n)) => Some((u, n))
    case _                  => None
  }
}

def viaLabel(props: Props): String = props.m.via match {
  case "mcp"      => "🤖 AI（MCP）"
  case "api"      => "API"
  case "schedule" => "⏱ 定期メッセージ"
  case _          => ""
}

def images(props: Props): List[AttachmentDto] = props.m.attachments.filter(_.contentType.startsWith("image/"))
def files(props: Props): List[AttachmentDto] = props.m.attachments.filterNot(_.contentType.startsWith("image/"))

def fileIcon(a: AttachmentDto): String = FileIcons.of(a.fileName, contentType = a.contentType)

def link(props: Props): String = s"${dom.window.location.origin}/m/${props.m.id}"

def copyLink(s: State, props: Props): Unit = { s.menu := false; Dom.copy(link(props), "メッセージへのリンクをコピーしました") }

def save(s: State, props: Props): Unit = {
  s.menu := false
  Toasts.report(Api.save(props.m.id, !props.m.saved)) { _ =>
    props.onChanged(props.m.copy(saved = !props.m.saved))
    Store.refreshSaved()
    Toasts.show(if (props.m.saved) "保存を外しました" else "保存しました（サイドバーの「保存済み」）")
  }
}

def pin(s: State, props: Props): Unit = {
  s.menu := false
  Toasts.report(Api.pin(props.m.id, !props.m.pinned))(_ => props.onChanged(props.m.copy(pinned = !props.m.pinned)))
}

def unread(s: State, props: Props): Unit = {
  s.menu := false
  Toasts.report(Api.markUnread(props.m.channelId, props.m.seq)) { _ => Store.reloadChannels(); Toasts.show("ここから未読にしました") }
}

def remove(s: State, props: Props): Unit = {
  s.menu := false
  Dialog.confirm("このメッセージを削除しますか？", "削除する") {
    Toasts.report(Api.deleteMessage(props.m.id))(_ => props.onRemoved(props.m.id))
  }
}

/** 編集中に貼った画像は本文に埋め込む（画像以外は編集では添付できない）. */
def uploadImage(props: Props)(f: dom.File): scala.concurrent.Future[Option[AttachmentDto]] = {
  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
  if (!f.`type`.startsWith("image/")) { Toasts.show("編集中に添付できるのは画像だけです", error = true); scala.concurrent.Future.successful(None) }
  else Api.upload(props.m.channelId, f).map(Some(_))
}

def saveEdit(s: State, props: Props): Unit = s.editor.v.foreach { ed =>
  if (!ed.isEmpty()) Toasts.report(Api.editMessage(props.m.id, Editor.fromJs(ed.getJSON()))) { n =>
    props.onEdit(None)
    props.onChanged(n)
  }
}

/** リアクションを付ける・外す（自分が付けていれば外す）. */
def react(s: State, props: Props, emoji: String): Unit = {
  s.picker := false
  val on = !props.m.reactions.exists(r => r.emoji == emoji && r.users.exists(u => Store.myId.contains(u.id)))
  Toasts.report(Api.react(props.m.id, emoji, on))(rs => props.onChanged(props.m.copy(reactions = rs)))
}

def reacted(r: ReactionDto): Boolean = r.users.exists(u => Store.myId.contains(u.id))
def chipClass(r: ReactionDto): String = if (reacted(r)) "react on" else "react"
def names(r: ReactionDto): String = r.users.map(_.name).mkString("、") + Reactions.customName(r.emoji).fold("")(n => s"（:$n:）")
def cls(props: Props): String =
  List("msg", if (props.compact) "compact" else "", if (props.highlight) "hl" else "", if (props.m.pinned) "pinned" else "",
    if (isSystem(props)) "system" else "").filter(_.nonEmpty).mkString(" ")
def edited(props: Props): Boolean = props.m.editedAt.isDefined
def replyLabel(props: Props): String = s"${props.m.replyCount}件の返信"
def lastReply(props: Props): String = props.m.lastReplyAt.map(t => "最終返信 " + Fmt.ago(t)).getOrElse("")
def quick: List[String] = List("👍", "🙏", "👀", "🎉")
// 下に余白がなければ（入力欄に近いメッセージ）、上に開く。一覧はスクロールする枠なので、はみ出すと切れて見えなくなる
def openMenu(s: State, e: dom.Event): Unit = { s.picker := false; s.up := Dom.spaceBelow(e) < 260; s.menu := !s.menu.v }
def openPicker(s: State, e: dom.Event): Unit = { s.menu := false; s.up := Dom.spaceBelow(e) < 400; s.picker := !s.picker.v }
def menuCls(s: State) = s.up.mapC(u => if (u) "menu up" else "menu")
def userOf(props: Props): Option[UserBrief] = props.m.author.map(a => iconUrl(props).fold(a)(u => a.copy(avatarUrl = Some(u))))
</script>

<template>
  <div :id='"msg-" + props.m.id' :class="cls(props)">
    <template v-if="isSystem(props)">
      <div class="sys"><span class="sys-time">{{ Fmt.time(props.m.createdAt) }}</span><span class="sys-text" v-html="props.m.html" v-rich="props.m.html"></span></div>
    </template>
    <template v-else>
      <div class="gutter">
        <template v-if="props.compact"><span class="ctime">{{ Fmt.time(props.m.createdAt) }}</span></template>
        <template v-else>
          <img v-if="iconImage(props).isDefined" :src='iconImage(props).getOrElse("")' alt="" class="emoji-img">
          <span v-else-if="iconChar(props).isDefined" class="emoji-icon">{{ iconChar(props).getOrElse("") }}</span>
          <Avatar v-else :user="userOf(props).map(person)" :size="36" />
        </template>
      </div>
      <div class="body">
        <div v-if="!props.compact" class="head">
          <b class="who">{{ name(props) }}</b>
          <span v-if="isBot(props)" class="app">APP</span>
          <a :href='"/m/" + props.m.id' class="time" :title="Fmt.dateTime(props.m.createdAt)" @click.prevent="copyLink(s, props)">{{ Fmt.time(props.m.createdAt) }}</a>
          <span v-if="viaLabel(props).nonEmpty" class="muted small">{{ viaLabel(props) }}</span>
          <span v-if="props.m.pinned" class="pin-mark">📌 ピン留め</span>
        </div>
        <div v-if="props.m.alsoChannel && !props.inThread" class="also muted small" @click="props.onReply(props.m)">スレッドへの返信</div>
        <div v-if="props.m.deleted" class="muted deleted">（このメッセージは削除されました）</div>
        <template v-if="!props.m.deleted">
          <div v-if="!props.editing" class="rich">
            <div v-html="props.m.html" v-rich="props.m.html"></div>
            <span v-if="edited(props)" class="muted small edited">（編集済み）</span>
          </div>
          <div v-if="props.editing" class="edit">
            <RichEditor :content="Some(props.m.body)" :channelId="Some(props.m.channelId)" :autofocus="true" :enterSubmit="true" :upload="Some(uploadImage(props))"
              :onReady="h => s.editor := Some(h)" :onSubmit="_ => saveEdit(s, props)" />
            <div class="row actions"><span class="muted small grow">Enter で保存・Esc でキャンセル</span>
              <button class="small" @click="props.onEdit(None)">キャンセル</button><button class="primary small" @click="saveEdit(s, props)">保存</button></div>
          </div>
          <div v-if="meeting(props).isDefined" class="meeting">
            <span class="mic"><span class="ico ico-video"></span></span>
            <div class="grow"><b>{{ meeting(props).map(_._2).getOrElse("") }}</b><div class="muted small">{{ AppNames.of("skaka") }} の Web 会議</div></div>
            <a class="btn primary small" :href='meeting(props).map(_._1).getOrElse("#")' target="_blank" rel="noopener">参加する</a>
          </div>
          <div v-if="images(props).nonEmpty" class="images">
            <a v-for="a in images(props)" :key="a.id" :href="a.url" target="_blank" rel="noopener" class="img"><img :src="a.url" :alt="a.fileName" loading="lazy"></a>
          </div>
          <div v-if="files(props).nonEmpty" class="files">
            <a v-for="a in files(props)" :key="a.id" :href="a.url" target="_blank" rel="noopener" class="file">
              <span class="ficon">{{ fileIcon(a) }}</span><span class="fname">{{ a.fileName }}</span><span class="muted small">{{ Fmt.bytes(a.size) }}</span>
            </a>
          </div>
          <div v-if="props.m.reactions.nonEmpty" class="reacts">
            <button v-for="r in props.m.reactions" :key="r.emoji" :class="chipClass(r)" :title="names(r)" @click="react(s, props, r.emoji)">
              <img v-if="Emojis.image(r.emoji).isDefined" :src='Emojis.image(r.emoji).getOrElse("")' :alt="r.emoji" class="cemoji">
              <template v-else>{{ r.emoji }}</template> {{ r.users.size }}</button>
          </div>
          <button v-if="props.m.replyCount > 0 && !props.inThread" class="replies" @click="props.onReply(props.m)">
            <Avatar v-for="u in props.m.replyUsers.take(4)" :key="u.id" :user="Some(person(u))" :size="20" />
            <span class="rc">{{ replyLabel(props) }}</span><span class="muted small">{{ lastReply(props) }}</span>
          </button>
        </template>
      </div>
      <div v-if="!props.m.deleted && !props.editing" class="tools">
        <button v-for="e in quick" class="tool" :title='e + " を付ける"' @click="react(s, props, e)">{{ e }}</button>
        <button class="tool" title="リアクション" @click="openPicker(s, event)">☺</button>
        <button v-if="!props.inThread" class="tool" title="スレッドで返信" @click="props.onReply(props.m)"><span class="ico ico-thread"></span></button>
        <button class="tool" :title='if (props.m.saved) "保存を外す" else "保存"' @click="save(s, props)"><span :class='if (props.m.saved) "ico ico-bookmark-fill saved" else "ico ico-bookmark"'></span></button>
        <button class="tool" title="その他" @click="openMenu(s, event)">⋯</button>
        <div v-if="s.picker" class="pick-wrap">
          <EmojiPicker :categories="Reactions.categories" :down="!s.up.v" :right="true" :onPick="e => react(s, props, e)" :onClose="_ => s.picker := false" />
        </div>
        <div v-if="s.menu" :class="menuCls(s)" @mouseleave="s.menu := false">
          <button @click="s.menu := false; s.remind := true">⏰ リマインド</button>
          <button v-if="props.canPost" @click="pin(s, props)">📌 {{ if (props.m.pinned) "ピン留めを外す" else "ピン留め" }}</button>
          <button @click="copyLink(s, props)">🔗 リンクをコピー</button>
          <button v-if="!props.inThread" @click="unread(s, props)">● ここから未読にする</button>
          <button v-if="mine(props) && props.canPost" @click="s.menu := false; props.onEdit(Some(props.m.id))">✏️ 編集</button>
          <button v-if="mine(props) || props.canManage" class="danger" @click="remove(s, props)">🗑 削除</button>
        </div>
      </div>
    </template>
    <ReminderDialog v-if="s.remind" :messageId="Some(props.m.id)" :onClose="_ => s.remind := false" />
  </div>
</template>

<style scoped>
.msg { position: relative; display: flex; gap: .6rem; padding: .35rem 1.1rem .35rem .9rem; }
.msg:hover { background: var(--msg-hover); }
.msg.compact { padding-top: .05rem; padding-bottom: .05rem; }
.msg.hl { background: var(--hl); box-shadow: inset 3px 0 0 var(--warn); }
.msg.pinned { background: var(--pin-bg); }
.gutter { width: 40px; flex: none; display: flex; justify-content: center; }
.ctime { visibility: hidden; font-size: .68rem; color: var(--muted); padding-top: .25rem; }
.msg:hover .ctime { visibility: visible; }
.emoji-icon { font-size: 1.7rem; line-height: 1; max-width: 40px; overflow: hidden; white-space: nowrap; }
.emoji-img { width: 36px; height: 36px; object-fit: contain; }
.body { flex: 1; min-width: 0; }
.head { display: flex; gap: .45rem; align-items: baseline; flex-wrap: wrap; }
.who { font-size: .95rem; }
.time { font-size: .74rem; color: var(--muted); }
.app { font-size: .6rem; border: 1px solid var(--muted); color: var(--muted); border-radius: 4px; padding: 0 .25rem; }
.pin-mark { font-size: .72rem; color: var(--warn); }
.also { cursor: pointer; }
.rich { overflow-wrap: anywhere; }
.edited { margin-left: .2rem; }
.deleted { font-style: italic; font-size: .9rem; }
.edit { margin: .3rem 0; }
.actions { margin-top: .35rem; gap: .4rem; }
.meeting { display: flex; gap: .7rem; align-items: center; border: 1px solid var(--line); border-radius: 10px; padding: .6rem .8rem; margin: .4rem 0;
  max-width: 460px; background: var(--card); }
.mic { font-size: 1.6rem; color: var(--accent); display: flex; }
.saved { color: var(--accent); }
.images { display: flex; gap: .4rem; flex-wrap: wrap; margin: .35rem 0; }
.img img { max-width: 360px; max-height: 260px; border-radius: 8px; border: 1px solid var(--line); display: block; object-fit: cover; }
.files { display: flex; flex-direction: column; gap: .3rem; margin: .35rem 0; }
.file { display: flex; gap: .5rem; align-items: center; border: 1px solid var(--line); border-radius: 8px; padding: .45rem .7rem; max-width: 420px;
  color: var(--fg); background: var(--card); }
.file:hover { text-decoration: none; border-color: var(--accent); }
.ficon { font-size: 1.3rem; }
.fname { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.reacts { display: flex; gap: .3rem; flex-wrap: wrap; margin-top: .3rem; }
.react { font-size: .85rem; padding: .08rem .5rem; border-radius: 999px; gap: .3rem; line-height: 1.35; }
.react.on { border-color: var(--accent); background: var(--accent-weak); color: var(--accent); }
.cemoji { width: 18px; height: 18px; object-fit: contain; vertical-align: -3px; }
.replies { display: inline-flex; gap: .35rem; align-items: center; margin-top: .3rem; border: 1px solid transparent; background: none; padding: .2rem .4rem;
  border-radius: 7px; }
.replies:hover { border-color: var(--line); background: var(--bg); }
.rc { color: var(--accent); font-weight: 700; font-size: .85rem; }
.tools { position: absolute; right: 1rem; top: -.9rem; display: none; gap: 1px; background: var(--bg); border: 1px solid var(--line);
  border-radius: 8px; padding: 2px; box-shadow: var(--shadow); z-index: 5; }
.msg:hover .tools { display: flex; }
.tool { border: none; background: none; padding: .15rem .35rem; font-size: .95rem; border-radius: 6px; }
.tool:hover { background: var(--hover); }
.pick-wrap { position: absolute; right: 0; top: 0; }
.menu { position: absolute; right: 0; top: 2rem; background: var(--bg); border: 1px solid var(--line); border-radius: 9px; box-shadow: var(--shadow);
  min-width: 210px; padding: .3rem; display: flex; flex-direction: column; z-index: 20; }
.menu button { border: none; background: none; justify-content: flex-start; padding: .4rem .6rem; border-radius: 6px; font-size: .88rem; }
.menu button:hover { background: var(--hover); }
.menu .danger { color: var(--danger); }
.menu.up { top: auto; bottom: 2rem; }
.msg.system { padding-top: .15rem; padding-bottom: .15rem; }
.sys { color: var(--muted); font-size: .85rem; padding-left: 50px; display: flex; gap: .5rem; align-items: baseline; }
.sys-time { font-size: .7rem; }
@media (max-width: 760px) {
  .msg { padding-right: .6rem; padding-left: .5rem; }
  .img img { max-width: 100%; }
  .tools { right: .4rem; }
}
</style>
