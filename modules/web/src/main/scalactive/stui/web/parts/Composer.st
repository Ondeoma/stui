<script lang="scala">
import stui.web.*
import io.circe.Json
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// メッセージの入力欄（WYSIWYG）. Enter で送信・Shift+Enter で改行. 添付・絵文字・会議・送信の予約.
// 入力中の内容は少し間を置いてサーバーに下書きとして保存する（チャンネル＋スレッドごと. 送ると消える）.
case class Props(channelId: UUID, rootId: Option[UUID] = None, placeholder: String = "", onSent: MessageDto => Unit = _ => (),
                 onEditLast: () => Unit = () => ())

case class State(act: ActionState,
                 editor: RV[Option[EditorHandle]] = RV(None),
                 files: RV[List[AttachmentDto]] = RV(Nil),
                 uploading: RV[Int] = RV(0),
                 also: RV[Boolean] = RV(false),
                 picker: RV[Boolean] = RV(false),
                 schedule: RV[Option[Json]] = RV(None),
                 draft: RV[Option[Option[Json]]] = RV(None),
                 pending: RV[Option[Int]] = RV(None),
                 last: RV[Option[Json]] = RV(None),
                 tb: RV[Boolean] = RV(Prefs.get("toolbar", if (dom.window.innerWidth < 760) "0" else "1") == "1"))

def initState() = State(useAction())

def mounted(s: State, props: Props): Unit =
  // 下書きを読んでから入力欄を出す（読めなくても空の入力欄は出す）
  Api.draft(props.channelId, props.rootId).onComplete(r => s.draft := Some(r.toOption.flatten.map(_.body)))

def unmounted(s: State, props: Props): Unit = flush(s, props)

/** 入力が止まって 1.5 秒後に下書きを保存する. */
def changed(s: State, props: Props, body: Json): Unit = {
  s.pending.v.foreach(dom.window.clearTimeout)
  s.last := Some(body)
  s.pending := Some(dom.window.setTimeout(() => flush(s, props), 1500))
}

def flush(s: State, props: Props): Unit = s.pending.v.foreach { t =>
  dom.window.clearTimeout(t)
  s.pending := None
  s.last.v.foreach(b => Api.saveDraft(props.channelId, props.rootId, b))
}

/** ファイルを先にアップロードする. 画像は本文のその位置に埋め込み（大きさ・寄せ方向を変えられる）、それ以外は投稿の添付に入れる. */
def upload(s: State, props: Props)(f: dom.File): Future[Option[AttachmentDto]] = {
  s.uploading := s.uploading.v + 1
  Api.upload(props.channelId, f).transform { r =>
    s.uploading := s.uploading.v - 1
    r.failed.foreach(Toasts.error)
    Success(r.toOption.flatMap { a =>
      if (inlineImage(a)) Some(a)
      else { s.files := s.files.v :+ a; None }
    })
  }
}

/** 本文に埋め込む画像（SVG は埋め込まない）. */
def inlineImage(a: AttachmentDto): Boolean = a.contentType.startsWith("image/") && a.contentType != "image/svg+xml"

def removeFile(s: State, id: UUID): Unit = s.files := s.files.v.filterNot(_.id == id)

def send(s: State, props: Props): Unit = s.editor.v.foreach { ed =>
  if ((!ed.isEmpty() || s.files.v.nonEmpty) && !s.act.busy.v && s.uploading.v == 0) {
    s.pending.v.foreach(dom.window.clearTimeout)
    s.pending := None
    val body = Editor.fromJs(ed.getJSON())
    Toasts.report(s.act.run(Api.postMessage(props.channelId,
      PostMessageReq(body, props.rootId, Option.when(s.also.v)(true), Option.when(s.files.v.nonEmpty)(s.files.v.map(_.id)))))) { m =>
      reset(s)
      props.onSent(m)
      Store.changed()
    }
  }
}

def emoji(s: State, e: String): Unit = { s.picker := false; s.editor.v.foreach(_.insertText(e)) }

/** skaka に会議室を作って案内を投稿する（連携していなければ連携の画面へ）. */
def meeting(s: State, props: Props): Unit =
  Api.meeting(props.channelId, None, props.rootId).onComplete {
    case Success(m) => props.onSent(m); Store.changed()
    case Failure(ApiError(409, msg)) =>
      Dialog.confirm(msg + "\n連携の画面を開きますか？", "連携する", danger = false) {
        dom.window.location.href = "/api/integrations/skaka/connect?returnTo=" + Api.enc(dom.window.location.pathname)
      }
    case Failure(e) => Toasts.error(e)
  }

def openSchedule(s: State): Unit = s.editor.v.foreach { ed =>
  if (ed.isEmpty()) Toasts.show("先にメッセージを入力してください", error = true)
  else s.schedule := Some(Editor.fromJs(ed.getJSON()))
}

/** 入力欄を空にする（送信・予約の後）. 待っている下書きの保存も取り消す. */
def reset(s: State): Unit = {
  s.pending.v.foreach(dom.window.clearTimeout)
  s.pending := None
  s.last := None
  s.editor.v.foreach(_.clear())
  s.files := Nil
  s.also := false
}

/** 予約・定期メッセージを登録したら、入力欄と下書きを空にする. */
def scheduled(s: State, props: Props): Unit = {
  s.schedule := None
  reset(s)
  Api.deleteDraft(props.channelId, props.rootId)
}

def meetings = Store.config.mapC(_.exists(_.meetings))
def showFiles(s: State) = computed { s.files.v.nonEmpty || s.uploading.v > 0 }
def toggleTb(s: State): Unit = { s.tb := !s.tb.v; Prefs.set("toolbar", if (s.tb.v) "1" else "0") }
def boxClass(s: State) = s.tb.mapC(t => if (t) "box" else "box no-tb")
def isThread(props: Props): Boolean = props.rootId.isDefined
def sendClass(s: State) = s.act.busy.mapC(b => if (b) "primary send busy" else "primary send")
def channelLabel(props: Props): String = Store.channel(props.channelId).map(c => if (ChannelKind.isDm(c.kind)) "DM" else "#" + c.name).getOrElse("チャンネル")
</script>

<template>
  <div class="composer">
    <div v-for="d in s.draft.mapC(_.toList)" :class="boxClass(s)">
      <RichEditor :placeholder="props.placeholder" :content="d" :channelId="Some(props.channelId)" :enterSubmit="true"
        :upload="Some(upload(s, props))" :onArrowUp="Some(props.onEditLast)"
        :onReady="h => s.editor := Some(h)" :onSubmit="_ => send(s, props)" :onChange="j => changed(s, props, j)" />
      <div v-if="showFiles(s)" class="files">
        <span v-for="f in s.files" :key="f.id" class="chip">📎 {{ f.fileName }} <span class="muted">{{ Fmt.bytes(f.size) }}</span>
          <button class="x" title="外す" @click="removeFile(s, f.id)">×</button></span>
        <span v-if="s.uploading.mapC(_ > 0)" class="muted small">アップロード中…</span>
      </div>
      <div class="bar">
        <button class="ghost tb aa" title="書式のツールバーを出す・隠す" @click="toggleTb(s)">Aa</button>
        <button class="ghost tb" title="ファイル・画像を添付（貼り付け・ドロップでも。画像は本文に埋め込みます）" @click="s.editor.v.foreach(_.pickFile())"><span class="ico ico-paperclip"></span></button>
        <span class="pick-wrap">
          <button class="ghost tb" title="絵文字" @click="s.picker := !s.picker.v">😀</button>
          <EmojiPicker :categories="Reactions.categories" v-if="s.picker" :onPick="e => emoji(s, e)" :onClose="_ => s.picker := false" />
        </span>
        <button class="ghost tb" title="メンション" @click='s.editor.v.foreach(_.insertText("@"))'>＠</button>
        <button v-if="meetings" class="ghost tb" :title='"Web 会議を始める（" + AppNames.nameOf("skaka") + "）"' @click="meeting(s, props)"><span class="ico ico-video"></span></button>
        <label v-if="isThread(props)" class="also"><input type="checkbox" v-model="s.also"> {{ channelLabel(props) }} にも送信</label>
        <span class="grow"></span>
        <span v-if="!isThread(props)" class="hint">Enter で送信・Shift+Enter で改行</span>
        <button class="ghost tb" title="送信を予約（定期メッセージ）" @click="openSchedule(s)">⏱</button>
        <button :class="sendClass(s)" title="送信" :disabled="s.act.busy" @click="send(s, props)">➤</button>
      </div>
    </div>
    <ScheduleDialog v-for="b in s.schedule.mapC(_.toList)" :channelId="props.channelId" :rootId="props.rootId" :body="Some(b)"
      :onClose="_ => s.schedule := None" :onSaved="_ => scheduled(s, props)" />
  </div>
</template>

<style scoped>
.composer { padding: 0 1rem .9rem; flex: none; }
.box { border: 1px solid var(--line); border-radius: 10px; background: var(--bg); }
.box:focus-within { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-weak); }
.files { display: flex; flex-wrap: wrap; gap: .35rem; padding: .3rem .6rem; }
.chip { display: inline-flex; gap: .3rem; align-items: center; border: 1px solid var(--line); border-radius: 999px; padding: .1rem .55rem; font-size: .82rem;
  background: var(--card); }
.x { border: none; background: none; padding: 0 .2rem; font-size: .9rem; }
.bar { display: flex; align-items: center; gap: .15rem; padding: .25rem .4rem; border-top: 1px solid var(--line); }
.tb { padding: .2rem .45rem; font-size: 1rem; }
.pick-wrap { position: relative; }
.also { font-size: .8rem; color: var(--muted); display: inline-flex; gap: .3rem; align-items: center; margin-left: .4rem; }
.hint { font-size: .72rem; color: var(--muted); margin-right: .4rem; white-space: nowrap; }
.aa { font-weight: 700; font-size: .85rem; }
@media (max-width: 1100px) { .hint { display: none; } }
.send { padding: .3rem .8rem; border-radius: 8px; }
.send.busy { opacity: .6; }
@media (max-width: 760px) { .composer { padding: 0 .4rem .5rem; } }
</style>
