<script lang="scala">
import stui.web.*
import io.circe.syntax.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// チャンネルの設定: 概要（名前・トピック・説明・アーカイブ）・通知・メンバー・連携（Webhook・定期メッセージ）
case class Props(channel: ChannelDto, onClose: Unit => Unit = _ => (), onChanged: ChannelDto => Unit = _ => ())

case class State(tab: RV[String], name: RV[String], topic: RV[String], purpose: RV[String], notifyLevel: RV[String], muted: RV[Boolean],
                 members: RV[List[ChannelMemberDto]] = RV(Nil), incoming: RV[List[IncomingHookDto]] = RV(Nil),
                 outgoing: RV[List[OutgoingHookDto]] = RV(Nil), scheduled: RV[List[ScheduledDto]] = RV(Nil),
                 hookName: RV[String] = RV(""), hookIcon: RV[String] = RV("🤖"), outName: RV[String] = RV(""), outUrl: RV[String] = RV(""),
                 outWords: RV[String] = RV(""), newUrl: RV[String] = RV(""), editSched: RV[Option[ScheduledDto]] = RV(None),
                 newSched: RV[Boolean] = RV(false))

def initState(props: Props) = State(RV(if (ChannelKind.isDm(props.channel.kind)) "notify" else "general"), RV(props.channel.name),
  RV(props.channel.topic), RV(props.channel.purpose), RV(props.channel.notifyLevel), RV(props.channel.muted))

def c(props: Props): ChannelDto = props.channel
def isDm(props: Props): Boolean = ChannelKind.isDm(props.channel.kind)
def canManage(props: Props): Boolean = props.channel.role.contains(ChannelRole.Admin) || Store.me.v.exists(_.isAdmin)
def isAdmin: Boolean = Store.me.v.exists(_.isAdmin)

def mounted(s: State, props: Props): Unit = { loadMembers(s, props); Emojis.load() }

def tab(s: State, props: Props, t: String): Unit = {
  s.tab := t
  if (t == "hooks") loadHooks(s, props)
}

def loadMembers(s: State, props: Props): Unit = Api.members(props.channel.id).foreach(s.members := _)

def loadHooks(s: State, props: Props): Unit = {
  if (canManage(props)) {
    Api.get[List[IncomingHookDto]](s"/api/hooks/incoming?channelId=${props.channel.id}").foreach(s.incoming := _)
    Api.get[List[OutgoingHookDto]]("/api/hooks/outgoing").foreach(hs => s.outgoing := hs.filter(_.channel.exists(_.id == props.channel.id)))
  }
  Api.scheduled(Some(props.channel.id)).foreach(s.scheduled := _)
}

def saveGeneral(s: State, props: Props): Unit =
  Toasts.report(Api.updateChannel(props.channel.id, UpdateChannelReq(Option.when(canManage(props))(s.name.v), Some(s.topic.v), Some(s.purpose.v), None, None))) { n =>
    Store.replace(n); props.onChanged(n); Toasts.show("保存しました")
  }

def saveNotify(s: State, props: Props): Unit =
  Toasts.report(Api.prefs(props.channel.id, MemberPrefReq(None, Some(s.muted.v), Some(s.notifyLevel.v), None))) { n =>
    Store.replace(n); props.onChanged(n); Toasts.show("通知の設定を保存しました")
  }

def archive(props: Props, on: Boolean): Unit =
  Dialog.confirm(if (on) s"#${props.channel.name} をアーカイブしますか？（投稿できなくなります。メッセージは残ります）" else "アーカイブを戻しますか？",
    if (on) "アーカイブする" else "戻す") {
    Toasts.report(Api.updateChannel(props.channel.id, UpdateChannelReq(None, None, None, Some(on), None))) { n => Store.replace(n); props.onChanged(n) }
  }

def autoJoin(props: Props, on: Boolean): Unit =
  Toasts.report(Api.updateChannel(props.channel.id, UpdateChannelReq(None, None, None, None, Some(on)))) { n =>
    Store.replace(n); props.onChanged(n); Toasts.show(if (on) "全員を参加させ、新しい人も自動で参加するようにしました" else "自動参加をやめました")
  }

def voice(props: Props, on: Boolean): Unit =
  Toasts.report(Api.updateChannel(props.channel.id, UpdateChannelReq(None, None, None, None, None, Some(on)))) { n =>
    Store.replace(n); props.onChanged(n); Toasts.show(if (on) "ボイスチャンネルにしました" else "ボイスチャンネルをやめました")
  }

def remove(props: Props): Unit =
  Dialog.confirm(s"#${props.channel.name} を削除しますか？ メッセージもすべて消え、元に戻せません。", "削除する") {
    Toasts.report(Api.delete(s"/api/channels/${props.channel.id}")) { _ => props.onClose(()); Store.loadChannels(); Router.go("/") }
  }

def leave(props: Props): Unit =
  Dialog.confirm(s"#${props.channel.name} から退出しますか？", "退出する") {
    Toasts.report(Api.leave(props.channel.id)) { _ => props.onClose(()); Store.loadChannels(); Router.go("/") }
  }

def hide(props: Props): Unit =
  Toasts.report(Api.prefs(props.channel.id, MemberPrefReq(None, None, None, Some(true)))) { _ => props.onClose(()); Store.loadChannels(); Router.go("/") }

def addMember(s: State, props: Props, u: UserBrief): Unit =
  Toasts.report(Api.addMembers(props.channel.id, List(u.id))) { _ => Store.forgetMembers(props.channel.id); loadMembers(s, props) }

def removeMember(s: State, props: Props, m: ChannelMemberDto): Unit =
  Dialog.confirm(s"${m.user.name} さんをチャンネルから外しますか？", "外す") {
    Toasts.report(Api.delete(s"/api/channels/${props.channel.id}/members/${m.user.id}")) { _ => Store.forgetMembers(props.channel.id); loadMembers(s, props) }
  }

def setRole(s: State, props: Props, m: ChannelMemberDto): Unit = {
  val role = if (m.role == ChannelRole.Admin) ChannelRole.Member else ChannelRole.Admin
  Toasts.report(Api.putUnit(s"/api/channels/${props.channel.id}/members/${m.user.id}/role", SetRoleReq(role).asJson))(_ => loadMembers(s, props))
}

def addIncoming(s: State, props: Props): Unit =
  if (s.hookName.v.trim.isEmpty) Toasts.show("名前を入力してください", error = true)
  else Toasts.report(Api.post[CreatedIncomingHookDto]("/api/hooks/incoming",
    IncomingHookReq(props.channel.id, s.hookName.v.trim, None, None, true, Some(s.hookIcon.v)).asJson)) { h =>
    s.newUrl := h.url; s.hookName := ""; loadHooks(s, props)
  }

/** 作った受信の Webhook のアイコンを変える. */
def setIcon(s: State, props: Props, h: IncomingHookDto, icon: String): Unit =
  Toasts.report(Api.putUnit(s"/api/hooks/incoming/${h.id}",
    IncomingHookReq(h.channel.id, h.name, h.username, None, h.enabled, Some(icon)).asJson))(_ => loadHooks(s, props))

def iconCls(s: State, e: String) = s.hookIcon.mapC(x => if (x == e) "ic-btn on" else "ic-btn")
/** 選べるカスタム絵文字（:name:）. */
def customs = Emojis.custom.mapC(_.map(c => s":${c.name}:" -> c))
def selectIcons: List[(String, String)] = HookIcons.all ++ Emojis.custom.v.map(c => s":${c.name}:" -> s":${c.name}:")

def deleteIncoming(s: State, props: Props, h: IncomingHookDto): Unit = Dialog.confirm(s"受信の Webhook「${h.name}」を削除しますか？", "削除する") {
  Toasts.report(Api.delete(s"/api/hooks/incoming/${h.id}"))(_ => loadHooks(s, props))
}

def regenerate(s: State, props: Props, h: IncomingHookDto): Unit = Dialog.confirm("URL を作り直しますか？（前の URL は使えなくなります）", "作り直す") {
  Toasts.report(Api.post[CreatedIncomingHookDto](s"/api/hooks/incoming/${h.id}/token")) { n => s.newUrl := n.url; loadHooks(s, props) }
}

def addOutgoing(s: State, props: Props): Unit =
  Toasts.report(Api.post[List[OutgoingHookDto]]("/api/hooks/outgoing", OutgoingHookReq(Some(props.channel.id), s.outName.v.trim, s.outUrl.v.trim,
    List(EventKind.MessageCreated), s.outWords.v.split("[,、\\s]+").map(_.trim).filter(_.nonEmpty).toList, true).asJson)) { _ =>
    s.outName := ""; s.outUrl := ""; s.outWords := ""; loadHooks(s, props)
  }

def deleteOutgoing(s: State, props: Props, h: OutgoingHookDto): Unit = Dialog.confirm(s"送信の Webhook「${h.name}」を削除しますか？", "削除する") {
  Toasts.report(Api.delete(s"/api/hooks/outgoing/${h.id}"))(_ => loadHooks(s, props))
}

def deleteSched(s: State, props: Props, x: ScheduledDto): Unit = Dialog.confirm("この定期メッセージを削除しますか？", "削除する") {
  Toasts.report(Api.delete(s"/api/scheduled/${x.id}"))(_ => loadHooks(s, props))
}

def schedLabel(x: ScheduledDto): String = Fmt.schedule(x) + (if (!x.enabled) "（停止中）" else "")

def mineSched(x: ScheduledDto): Boolean = x.author.exists(a => Store.myId.contains(a.id)) || isAdmin

def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "tab active" else "tab")
def levels: List[(String, String)] = NotifyLevel.all.map(l => l -> NotifyLevel.label(l))
def baseUrl: String = org.scalajs.dom.window.location.origin
</script>

<template>
  <Modal :onClose="props.onClose" :wide="true">
    <h2>{{ Store.prefix(props.channel.kind) }}{{ Store.title(props.channel) }}</h2>
    <nav class="tabs">
      <button v-if="!isDm(props)" :class='tabCls(s, "general")' @click='tab(s, props, "general")'>概要</button>
      <button :class='tabCls(s, "notify")' @click='tab(s, props, "notify")'>通知</button>
      <button v-if="!isDm(props)" :class='tabCls(s, "members")' @click='tab(s, props, "members")'>メンバー</button>
      <button v-if="!isDm(props)" :class='tabCls(s, "hooks")' @click='tab(s, props, "hooks")'>連携・定期メッセージ</button>
    </nav>

    <div v-if='s.tab.mapC(_ == "general")' class="tabbody">
      <label class="field">名前<input type="text" v-model="s.name" :disabled="!canManage(props)" maxlength="80"></label>
      <label class="field">トピック<input type="text" v-model="s.topic" maxlength="250"></label>
      <label class="field">説明<input type="text" v-model="s.purpose" maxlength="250"></label>
      <div class="actions left"><button class="primary" @click="saveGeneral(s, props)">保存</button></div>
      <hr>
      <div class="danger-zone">
        <button v-if="canManage(props) && !props.channel.archived" @click="archive(props, true)">アーカイブする</button>
        <button v-if="canManage(props) && props.channel.archived" @click="archive(props, false)">アーカイブを戻す</button>
        <button v-if="isAdmin && !props.channel.autoJoin" @click="autoJoin(props, true)">全員を自動で参加させる</button>
        <button v-if="isAdmin && props.channel.autoJoin" @click="autoJoin(props, false)">自動参加をやめる</button>
        <button v-if="canManage(props) && !isDm(props) && !props.channel.voice" @click="voice(props, true)">🔊 ボイスチャンネルにする</button>
        <button v-if="canManage(props) && !isDm(props) && props.channel.voice" @click="voice(props, false)">ボイスチャンネルをやめる</button>
        <button v-if="props.channel.member" @click="leave(props)">退出する</button>
        <button v-if="isAdmin" class="danger" @click="remove(props)">チャンネルを削除</button>
      </div>
    </div>

    <div v-if='s.tab.mapC(_ == "notify")' class="tabbody">
      <div class="field">通知する
        <label v-for="l in levels" class="check"><input type="radio" name="lv" :value="l._1" v-model="s.notifyLevel"> {{ l._2 }}</label>
      </div>
      <label class="check"><input type="checkbox" v-model="s.muted"> ミュート（未読を目立たせず、@ユーザー名 のメンション以外は通知しない）</label>
      <div class="actions left"><button class="primary" @click="saveNotify(s, props)">保存</button>
        <button v-if="isDm(props)" @click="hide(props)">一覧から隠す</button></div>
    </div>

    <div v-if='s.tab.mapC(_ == "members")' class="tabbody">
      <UserPicker placeholder="追加する人を探す" :search="UserSearch.search" :delay="150" :onPick="p => UserSearch(p).foreach(addMember(s, props, _))" />
      <div class="members">
        <div v-for="m in s.members" :key="m.user.id" class="member">
          <Avatar :user="Some(person(m.user))" :size="26" /><span class="grow">{{ m.user.name }}</span>
          <span v-if='m.role == "admin"' class="muted small">管理者</span>
          <button v-if="canManage(props) && !m.user.bot" class="small ghost" @click="setRole(s, props, m)">{{ if (m.role == "admin") "管理者を外す" else "管理者にする" }}</button>
          <button v-if="canManage(props)" class="small ghost" @click="removeMember(s, props, m)">外す</button>
        </div>
      </div>
    </div>

    <div v-if='s.tab.mapC(_ == "hooks")' class="tabbody">
      <template v-if="canManage(props)">
        <h3>受信の Webhook</h3>
        <p class="muted small">URL に JSON（<code>{"text": "メッセージ"}</code>。Slack・Mattermost と同じ形）を POST すると、このチャンネルに投稿されます。</p>
        <div v-if="s.newUrl.mapC(_.nonEmpty)" class="newurl">
          <code class="grow">{{ s.newUrl }}</code><button class="small" @click="Dom.copy(s.newUrl.v)">コピー</button>
          <div class="muted small">この URL は今だけ表示されます。控えておいてください。</div>
        </div>
        <div v-for="h in s.incoming" :key="h.id" class="hook">
          <select class="ic-sel" title="アイコン" @change="setIcon(s, props, h, Dom.value(event))">
            <option v-for="i in selectIcons" :value="i._1" :selected='h.iconEmoji.getOrElse("🤖") == i._1'>{{ i._2 }}</option>
          </select>
          <b>{{ h.name }}</b><span class="muted small">{{ baseUrl }}/hooks/{{ h.tokenHint }}…</span><span class="grow"></span>
          <button class="small ghost" @click="regenerate(s, props, h)">URL を作り直す</button>
          <button class="small ghost" @click="deleteIncoming(s, props, h)">削除</button>
        </div>
        <form class="add-in" @submit.prevent="addIncoming(s, props)">
          <div class="icons">
            <span class="muted small">アイコン</span>
            <button v-for="i in HookIcons.all" type="button" :class="iconCls(s, i._1)" :title="i._2" @click="s.hookIcon := i._1">{{ i._1 }}</button>
            <button v-for="c in customs" type="button" :class="iconCls(s, c._1)" :title="c._1" @click="s.hookIcon := c._1"><img :src="c._2.url" alt="" class="cimg"></button>
          </div>
          <div class="row"><input type="text" v-model="s.hookName" class="grow" placeholder="名前（投稿者として表示）"><button type="submit" class="small">追加</button></div>
          <p class="muted small">カスタム絵文字は、リアクションの絵文字の選択の「カスタム」で登録できます。</p>
        </form>
        <h3>送信の Webhook</h3>
        <p class="muted small">このチャンネルへの投稿を JSON で POST します（<code>X-Stui-Signature</code> に HMAC-SHA256 の署名）。応答に <code>{"text": …}</code> があれば返信として投稿します。</p>
        <div v-for="h in s.outgoing" :key="h.id" class="hook">
          <b>{{ h.name }}</b><span class="muted small ell">{{ h.url }}</span>
          <span v-if="h.triggerWords.nonEmpty" class="muted small">（{{ h.triggerWords.mkString("、") }} で始まるもの）</span><span class="grow"></span>
          <button class="small ghost" @click="Dom.copy(h.secret, &quot;署名の鍵をコピーしました&quot;)">署名の鍵</button>
          <button class="small ghost" @click="deleteOutgoing(s, props, h)">削除</button>
        </div>
        <form class="row add" @submit.prevent="addOutgoing(s, props)">
          <input type="text" v-model="s.outName" placeholder="名前"><input type="url" v-model="s.outUrl" placeholder="https://…">
          <input type="text" v-model="s.outWords" placeholder="トリガーの語（任意。カンマ区切り）">
          <button type="submit" class="small">追加</button>
        </form>
      </template>
      <h3>定期メッセージ</h3>
      <div v-for="x in s.scheduled" :key="x.id" class="hook">
        <span class="grow"><b>{{ schedLabel(x) }}</b> <span class="muted small">{{ x.author.map(_.name).getOrElse("") }}</span>
          <span class="muted small ell">{{ Html.text(x.html).take(60) }}</span></span>
        <button v-if="mineSched(x)" class="small ghost" @click="s.editSched := Some(x)">変える</button>
        <button v-if="mineSched(x)" class="small ghost" @click="deleteSched(s, props, x)">削除</button>
      </div>
      <button v-if="props.channel.member" class="small" @click="s.newSched := true">＋ 定期メッセージを追加</button>
    </div>
  </Modal>
  <ScheduleDialog v-for="x in s.editSched.mapC(_.toList)" :channelId="props.channel.id" :editing="Some(x)" :onClose="_ => s.editSched := None"
    :onSaved="_ => loadHooks(s, props)" />
  <ScheduleDialog v-if="s.newSched" :channelId="props.channel.id" :onClose="_ => s.newSched := false" :onSaved="_ => loadHooks(s, props)" />
</template>

<style scoped>
h2 { margin: 0 0 .6rem; font-size: 1.15rem; }
h3 { font-size: .95rem; margin: 1.2rem 0 .3rem; }
.tabbody { min-height: 260px; }
.left { justify-content: flex-start; }
.danger-zone { display: flex; flex-wrap: wrap; gap: .5rem; }
.danger { color: var(--danger); border-color: var(--danger); }
.members { margin-top: .6rem; max-height: 50vh; overflow-y: auto; }
.member { display: flex; align-items: center; gap: .5rem; padding: .3rem .2rem; border-bottom: 1px solid var(--line); }
.hook { display: flex; align-items: center; gap: .5rem; padding: .35rem 0; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.ell { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 20rem; }
.add { gap: .4rem; margin-top: .5rem; flex-wrap: wrap; }
.add-in { margin-top: .6rem; display: flex; flex-direction: column; gap: .4rem; }
.icons { display: flex; flex-wrap: wrap; gap: .2rem; align-items: center; }
.icons .muted { margin-right: .3rem; }
.ic-btn { font-size: 1.15rem; padding: .1rem .35rem; border-color: transparent; background: transparent; }
.ic-btn.on { border-color: var(--accent); background: var(--accent-weak); }
.ic-sel { font-size: 1.05rem; padding: .1rem .2rem; }
.cimg { width: 20px; height: 20px; object-fit: contain; display: block; }
.newurl { display: flex; flex-wrap: wrap; gap: .4rem; align-items: center; background: var(--accent-weak); border-radius: 8px; padding: .5rem .7rem; margin: .4rem 0; }
.newurl code { word-break: break-all; }
</style>
