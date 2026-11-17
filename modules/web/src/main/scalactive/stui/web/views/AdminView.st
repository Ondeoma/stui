<script lang="scala">
import stui.web.*
import io.circe.syntax.*
import stui.shared.*
import stui.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 管理（システム管理者）: ユーザー・チャンネル・全体の Webhook・監査ログ
case class State(tab: RV[String] = RV("users"),
                 users: RV[List[UserDto]] = RV(Nil),
                 q: RV[String] = RV(""),
                 channels: RV[List[ChannelDto]] = RV(Nil),
                 hooks: RV[List[OutgoingHookDto]] = RV(Nil),
                 hookName: RV[String] = RV(""),
                 hookUrl: RV[String] = RV(""),
                 audit: RV[List[AuditDto]] = RV(Nil))

def initState() = State()

def load(s: State): Unit = {
  Api.get[List[UserDto]]("/api/admin/users" + Api.query("q" -> Some(s.q.v))).foreach(s.users := _)
  Api.get[List[ChannelDto]]("/api/admin/channels").foreach(s.channels := _)
  Api.get[List[OutgoingHookDto]]("/api/hooks/outgoing").foreach(hs => s.hooks := hs.filter(_.channel.isEmpty))
  Api.get[List[AuditDto]]("/api/admin/audit?limit=200").foreach(s.audit := _)
}

def mounted(s: State): Unit = { pageTitle("管理"); load(s) }

def setUser(s: State, u: UserDto, admin: Option[Boolean], disabled: Option[Boolean]): Unit =
  Store.report(Api.patchUnit(s"/api/admin/users/${u.id}", UpdateUserReq(admin, disabled).asJson))(_ => load(s))

def archive(s: State, c: ChannelDto, on: Boolean): Unit =
  Store.report(Api.updateChannel(c.id, UpdateChannelReq(None, None, None, Some(on), None))) { _ => load(s); Store.loadChannels() }

def remove(s: State, c: ChannelDto): Unit =
  Dialog.prompt(s"#${c.name} とすべてのメッセージを完全に削除します。元に戻せません。\n確認のため名前を入力してください。", "", "完全に削除する") { k =>
    if (k.trim == c.name) Store.report(Api.delete(s"/api/channels/${c.id}")) { _ => load(s); Store.loadChannels() }
    else Store.toast("名前が一致しないため削除しませんでした", error = true)
  }

def addHook(s: State): Unit =
  Store.report(Api.post[List[OutgoingHookDto]]("/api/hooks/outgoing", OutgoingHookReq(None, s.hookName.v.trim, s.hookUrl.v.trim, Nil, Nil, true).asJson)) { _ =>
    s.hookName := ""; s.hookUrl := ""; load(s)
  }

def removeHook(s: State, h: OutgoingHookDto): Unit = Dialog.confirm(s"Webhook「${h.name}」を削除しますか？", "削除する") {
  Store.report(Api.delete(s"/api/hooks/outgoing/${h.id}"))(_ => load(s))
}

def tab(s: State, v: String) = s.tab.mapC(x => if (x == v) "active" else "")
def isTab(s: State, v: String) = s.tab.mapC(_ == v)
def me(u: UserDto): Boolean = Store.myId.contains(u.id)
</script>

<template>
  <div class="page">
    <h1>管理</h1>
    <nav class="tabs">
      <button :class='tab(s, "users")' @click='s.tab := "users"'>ユーザー</button>
      <button :class='tab(s, "channels")' @click='s.tab := "channels"'>チャンネル</button>
      <button :class='tab(s, "hooks")' @click='s.tab := "hooks"'>全体の Webhook</button>
      <button :class='tab(s, "audit")' @click='s.tab := "audit"'>監査ログ</button>
    </nav>
    <div v-if='isTab(s, "users")'>
      <form class="row" @submit.prevent="load(s)"><input type="search" v-model="s.q" placeholder="名前・メールで絞り込み"><button type="submit">検索</button></form>
      <p class="muted small">ユーザーは初めてログインしたときに作られ、自動参加のチャンネルに参加します。
        IdP のグループ（ADMIN_GROUPS）に入っている人は、ここでの設定によらず管理者です。</p>
      <div class="table-wrap">
        <table class="list">
          <thead><tr><th>名前</th><th>メール</th><th>グループ</th><th>最終ログイン</th><th>管理者</th><th>無効</th></tr></thead>
          <tbody>
            <tr v-for="u in s.users" :key="u.id">
              <td><Avatar :user="Some(UserBrief(u.id, u.name, u.avatarUrl, u.bot))" :size="22" /> {{ u.name }}</td>
              <td class="small">{{ u.email.getOrElse("") }}</td>
              <td class="small">{{ u.groups.mkString(", ") }}</td>
              <td class="small">{{ u.lastLoginAt.map(Fmt.ago).getOrElse("") }}</td>
              <td><input type="checkbox" :checked="u.isAdmin" :disabled="me(u)" @change="setUser(s, u, Some(Dom.checked(event)), None)"></td>
              <td><input type="checkbox" :checked="u.disabled" :disabled="me(u)" @change="setUser(s, u, None, Some(Dom.checked(event)))"></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if='isTab(s, "channels")'>
      <p class="muted small">非公開チャンネルは、メンバーでなければ名前だけが見えます。DM はここに出ません。</p>
      <div class="table-wrap">
        <table class="list">
          <thead><tr><th>名前</th><th>種類</th><th>人数</th><th>最終投稿</th><th></th></tr></thead>
          <tbody>
            <tr v-for="c in s.channels" :key="c.id">
              <td><a :href='"/c/" + c.id' @click.prevent="Store.openChannel(c.id)">{{ Store.prefix(c.kind) }}{{ c.name }}</a>
                <span v-if="c.archived" class="badge">アーカイブ</span><span v-if="c.autoJoin" class="badge">自動参加</span></td>
              <td class="small">{{ if (c.kind == "private") "非公開" else "公開" }}</td>
              <td class="small">{{ c.memberCount }}</td>
              <td class="small">{{ c.lastMessageAt.map(Fmt.ago).getOrElse("") }}</td>
              <td class="nowrap">
                <button class="small" @click="archive(s, c, !c.archived)">{{ if (c.archived) "アーカイブを解除" else "アーカイブ" }}</button>
                <button class="danger small" @click="remove(s, c)">削除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if='isTab(s, "hooks")'>
      <p class="muted small">全体の送信 Webhook は、すべての公開チャンネルのイベント（投稿・編集・削除・リアクション・チャンネルの作成・参加・退出）を POST します。
        チャンネルごとの Webhook は、各チャンネルの ⚙ →「連携・定期メッセージ」で設定します。</p>
      <div v-for="h in s.hooks" :key="h.id" class="hook row">
        <b>{{ h.name }}</b><span class="muted small grow">{{ h.url }}</span>
        <button class="small ghost" @click="Dom.copy(h.secret, &quot;署名の鍵をコピーしました&quot;)">署名の鍵</button>
        <button class="small danger" @click="removeHook(s, h)">削除</button>
      </div>
      <form class="row" @submit.prevent="addHook(s)">
        <input type="text" v-model="s.hookName" placeholder="名前"><input type="url" v-model="s.hookUrl" placeholder="https://…" class="grow">
        <button type="submit" class="small">追加</button>
      </form>
    </div>
    <div v-if='isTab(s, "audit")' class="table-wrap">
      <table class="list">
        <thead><tr><th>日時</th><th>操作者</th><th>経路</th><th>操作</th><th>対象</th><th>詳細</th></tr></thead>
        <tbody>
          <tr v-for="a in s.audit" :key="a.id">
            <td class="small nowrap">{{ Fmt.dateTime(a.at) }}</td>
            <td class="small">{{ a.actor.map(_.name).getOrElse("") }}</td>
            <td class="small">{{ a.via }}</td>
            <td><code class="small">{{ a.action }}</code></td>
            <td class="small key">{{ a.target.getOrElse("").take(8) }}</td>
            <td class="small muted">{{ a.detail.noSpaces.take(120) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
