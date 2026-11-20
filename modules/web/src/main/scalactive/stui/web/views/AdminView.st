<script lang="scala">
import stui.web.*
import io.circe.syntax.*
import stui.shared.*
import stui.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}
import java.util.UUID

// 管理（システム管理者）: ユーザー・チャンネル・エージェント専用アカウント・全体の Webhook・監査ログ
case class State(tab: RV[String] = RV("users"),
                 users: RV[List[UserDto]] = RV(Nil),
                 q: RV[String] = RV(""),
                 channels: RV[List[ChannelDto]] = RV(Nil),
                 hooks: RV[List[OutgoingHookDto]] = RV(Nil),
                 hookName: RV[String] = RV(""),
                 hookUrl: RV[String] = RV(""),
                 audit: RV[List[AuditDto]] = RV(Nil),
                 agents: RV[List[AgentDto]] = RV(Nil),
                 agentName: RV[String] = RV(""),
                 keyFor: RV[Option[UUID]] = RV(None),
                 keyName: RV[String] = RV("skakapo"),
                 keyScopes: RV[List[String]] = RV(List("read", "write")),
                 keyDays: RV[String] = RV(""),
                 keyError: RV[String] = RV(""),
                 created: RV[Option[String]] = RV(None),
                 joinFor: RV[Option[UUID]] = RV(None),
                 joinIds: RV[List[UUID]] = RV(Nil))

def initState() = State()

def load(s: State): Unit = {
  Api.get[List[UserDto]]("/api/admin/users" + Api.query("q" -> Some(s.q.v))).foreach(s.users := _)
  Api.get[List[ChannelDto]]("/api/admin/channels").foreach(s.channels := _)
  Api.get[List[OutgoingHookDto]]("/api/hooks/outgoing").foreach(hs => s.hooks := hs.filter(_.channel.isEmpty))
  Api.get[List[AuditDto]]("/api/admin/audit?limit=200").foreach(s.audit := _)
  loadAgents(s)
}

// ---------------------------------------------------------------- エージェント専用アカウント
def loadAgents(s: State): Unit = Api.get[List[AgentDto]]("/api/admin/agents").foreach(s.agents := _)

def agentUrl(a: AgentDto, rest: String = ""): String = s"/api/admin/agents/${a.id}$rest"

def addAgent(s: State): Unit =
  Toasts.report(Api.post[AgentDto]("/api/admin/agents", CreateAgentReq(s.agentName.v).asJson)) { _ => s.agentName := ""; loadAgents(s) }

def renameAgent(s: State, a: AgentDto): Unit = Dialog.prompt("新しい名前", a.name, "変更する") { n =>
  Toasts.report(Api.patch[AgentDto](agentUrl(a), UpdateAgentReq(Some(n), None).asJson))(_ => loadAgents(s))
}

def setAgentDisabled(s: State, a: AgentDto, on: Boolean): Unit =
  Toasts.report(Api.patch[AgentDto](agentUrl(a), UpdateAgentReq(None, Some(on)).asJson))(_ => loadAgents(s))

def openKey(s: State, a: AgentDto): Unit = { s.keyFor := Some(a.id); s.joinFor := None; s.keyError := ""; s.created := None }

def issueKey(s: State, a: AgentDto): Unit = {
  s.keyError := ""
  Api.post[CreatedApiKeyDto](agentUrl(a, "/keys"), CreateApiKeyReq(s.keyName.v, s.keyScopes.v, s.keyDays.v.toIntOption).asJson).onComplete {
    case Success(r) => s.created := Some(r.key); s.keyFor := None; loadAgents(s)
    case Failure(e) => s.keyError := e.getMessage
  }
}

def revokeKey(s: State, a: AgentDto, k: AgentKeyDto): Unit =
  Dialog.confirm(s"「${a.name}」の APIキー「${k.name}」を取り消しますか？このキーを使っているエージェントは使えなくなります。", "取り消す") {
    Toasts.report(Api.delete(agentUrl(a, s"/keys/${k.id}")))(_ => loadAgents(s))
  }

def openJoin(s: State, a: AgentDto): Unit = { s.joinFor := Some(a.id); s.keyFor := None; s.joinIds := Nil }

def toggleJoin(s: State, c: ChannelDto, on: Boolean): Unit =
  s.joinIds := (if (on) (c.id :: s.joinIds.v).distinct else s.joinIds.v.filterNot(_ == c.id))

def join(s: State, a: AgentDto): Unit =
  Toasts.report(Api.post[AgentDto](agentUrl(a, "/channels"), AgentChannelsReq(s.joinIds.v).asJson)) { _ => s.joinFor := None; loadAgents(s) }

def leave(s: State, a: AgentDto, c: ChannelBrief): Unit = Dialog.confirm(s"「${a.name}」を ${Store.prefix(c.kind)}${c.name} から外しますか？", "外す") {
  Toasts.report(Api.send(org.scalajs.dom.HttpMethod.DELETE, agentUrl(a, s"/channels/${c.id}")))(_ => loadAgents(s))
}

/** 参加させられるチャンネル（アーカイブしていない、まだ参加していないもの）. */
def joinable(s: State, a: AgentDto) = s.channels.mapC(_.filter(c => !c.archived && !a.channels.exists(_.id == c.id)))
def isKeyFor(s: State, a: AgentDto) = s.keyFor.mapC(_.contains(a.id))
def isJoinFor(s: State, a: AgentDto) = s.joinFor.mapC(_.contains(a.id))
def keyState(k: AgentKeyDto): String =
  if (k.revoked) "取り消し済み" else k.expiresAt.map(t => s"${Fmt.dateTime(t)} まで").getOrElse("無期限")

def mounted(s: State): Unit = { pageTitle("管理"); load(s) }

def setUser(s: State, u: UserDto, admin: Option[Boolean], disabled: Option[Boolean]): Unit =
  Toasts.report(Api.patchUnit(s"/api/admin/users/${u.id}", UpdateUserReq(admin, disabled).asJson))(_ => load(s))

def archive(s: State, c: ChannelDto, on: Boolean): Unit =
  Toasts.report(Api.updateChannel(c.id, UpdateChannelReq(None, None, None, Some(on), None))) { _ => load(s); Store.loadChannels() }

def remove(s: State, c: ChannelDto): Unit =
  Dialog.prompt(s"#${c.name} とすべてのメッセージを完全に削除します。元に戻せません。\n確認のため名前を入力してください。", "", "完全に削除する") { k =>
    if (k.trim == c.name) Toasts.report(Api.delete(s"/api/channels/${c.id}")) { _ => load(s); Store.loadChannels() }
    else Toasts.show("名前が一致しないため削除しませんでした", error = true)
  }

def addHook(s: State): Unit =
  Toasts.report(Api.post[List[OutgoingHookDto]]("/api/hooks/outgoing", OutgoingHookReq(None, s.hookName.v.trim, s.hookUrl.v.trim, Nil, Nil, true).asJson)) { _ =>
    s.hookName := ""; s.hookUrl := ""; load(s)
  }

def removeHook(s: State, h: OutgoingHookDto): Unit = Dialog.confirm(s"Webhook「${h.name}」を削除しますか？", "削除する") {
  Toasts.report(Api.delete(s"/api/hooks/outgoing/${h.id}"))(_ => load(s))
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
      <button :class='tab(s, "agents")' @click='s.tab := "agents"'>エージェント</button>
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
              <td><Avatar :user="Some(Person(u.id.toString, u.name, u.avatarUrl))" :size="22" /> {{ u.name }}</td>
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
    <div v-if='isTab(s, "agents")'>
      <p class="muted small">エージェント専用アカウントは、skakapo などの外部の AI エージェントが {{ AppNames.self }} の MCP（<code>/mcp</code>）・REST API を呼ぶための、ログインしないアカウントです。
        ここで作って APIキーを発行し、エージェント側に登録します（admin スコープは付けられません）。</p>
      <p class="muted small">エージェントは<b>このアカウント自身の権限</b>で動きます。公開チャンネルにはエージェントが自分で参加できます。
        非公開チャンネルは、ここの「チャンネルに参加させる」で参加させてください。
        エージェントがした投稿・変更は、このアカウント（ボットの表示）が行ったものとして記録されます。通知（メール・プッシュ）は届きません。</p>
      <form class="row" @submit.prevent="addAgent(s)">
        <input type="text" v-model="s.agentName" placeholder="名前（例: skakapo）" maxlength="60" class="grow">
        <button type="submit" class="small">作成</button>
      </form>
      <div v-if="s.created.mapC(_.isDefined)" class="created">
        <p><b>APIキーを発行しました。</b>この画面を閉じると二度と表示されません。skakapo の管理画面の「サービス」→ このサービス →「エージェント専用アカウント」に貼り付けてください。</p>
        <div class="row"><code class="grow keytext">{{ s.created.mapC(_.getOrElse("")) }}</code><button class="small" @click="Dom.copy(s.created.v.getOrElse(&quot;&quot;))">コピー</button></div>
        <button class="small" @click="s.created := None">閉じる</button>
      </div>
      <p v-if="s.agents.mapC(_.isEmpty)" class="muted">エージェント専用アカウントはまだありません。</p>
      <section v-for="a in s.agents" :key="a.id" class="agent">
        <div class="row">
          <b>🤖 {{ a.name }}</b><span v-if="a.disabled" class="badge">無効</span>
          <span class="muted small grow">{{ Fmt.dateTime(a.createdAt) }} に作成</span>
          <button class="small ghost" @click="renameAgent(s, a)">名前を変える</button>
          <button class="small ghost" @click="setAgentDisabled(s, a, !a.disabled)">{{ if (a.disabled) "有効にする" else "無効にする" }}</button>
          <button class="small" @click="openJoin(s, a)">チャンネルに参加させる</button>
          <button class="small primary" @click="openKey(s, a)">APIキーを発行</button>
        </div>
        <div class="small">参加しているチャンネル:
          <span v-if="a.channels.isEmpty" class="muted">なし</span>
          <span v-for="c in a.channels" :key="c.id" class="chip">{{ Store.prefix(c.kind) }}{{ c.name }}
            <button class="ghost small" title="外す" @click="leave(s, a, c)">×</button></span>
        </div>
        <form v-if="isJoinFor(s, a)" class="sub" @submit.prevent="join(s, a)">
          <div class="joinlist">
            <label v-for="c in joinable(s, a)" :key="c.id" class="small check">
              <input type="checkbox" @change="toggleJoin(s, c, Dom.checked(event))"> {{ Store.prefix(c.kind) }}{{ c.name }}</label>
          </div>
          <div class="row"><button type="submit" class="small primary">参加させる</button><button type="button" class="small" @click="s.joinFor := None">やめる</button></div>
        </form>
        <form v-if="isKeyFor(s, a)" class="sub" @submit.prevent="issueKey(s, a)">
          <div class="row">
            <input type="text" v-model="s.keyName" placeholder="用途（例: skakapo）" class="grow">
            <Combo :bind="Some(s.keyDays)" :options='Opt.of(List("" -> "無期限", "30" -> "30日", "90" -> "90日", "365" -> "1年"))' />
          </div>
          <div class="row">
            <span class="small muted">スコープ:</span>
            <label class="small"><input type="checkbox" value="read" v-model="s.keyScopes"> 参照（read）</label>
            <label class="small"><input type="checkbox" value="write" v-model="s.keyScopes"> 作成・更新・削除（write）</label>
          </div>
          <p v-if="s.keyError.mapC(_.nonEmpty)" class="error">{{ s.keyError }}</p>
          <div class="row"><button type="submit" class="small primary">発行</button><button type="button" class="small" @click="s.keyFor := None">やめる</button></div>
        </form>
        <div class="table-wrap">
          <table class="list">
            <thead><tr><th>用途</th><th>キー</th><th>スコープ</th><th>有効期限</th><th>最終使用</th><th></th></tr></thead>
            <tbody>
              <tr v-for="k in a.keys" :key="k.id" :class='if (k.revoked) "muted" else ""'>
                <td>{{ k.name }}</td><td><code>{{ k.prefix }}…</code></td><td class="small">{{ k.scopes.mkString(", ") }}</td>
                <td class="small">{{ keyState(k) }}</td>
                <td class="small">{{ k.lastUsedAt.map(Fmt.ago).getOrElse("未使用") }}</td>
                <td><button v-if="!k.revoked" class="danger small" @click="revokeKey(s, a, k)">取り消す</button></td>
              </tr>
              <tr v-if="a.keys.isEmpty"><td colspan="6" class="muted">APIキーはまだありません</td></tr>
            </tbody>
          </table>
        </div>
      </section>
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

<style scoped>
.agent { border: 1px solid var(--line); border-radius: 8px; padding: .6rem .8rem; margin: .8rem 0; }
.agent .row { flex-wrap: wrap; }
.chip { display: inline-flex; align-items: center; gap: .2rem; border: 1px solid var(--line); border-radius: 999px; padding: 0 .5rem; margin: .1rem .2rem; }
.sub { border-top: 1px dashed var(--line); margin-top: .5rem; padding-top: .5rem; }
.joinlist { display: flex; flex-wrap: wrap; gap: .3rem 1rem; max-height: 12rem; overflow: auto; margin-bottom: .4rem; }
.created { border: 1px solid var(--ok); border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; background: color-mix(in srgb, var(--ok) 8%, transparent); }
.keytext { background: var(--side); padding: .3rem .5rem; border-radius: 5px; word-break: break-all; font-size: .8rem; }
</style>
