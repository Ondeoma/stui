<script lang="scala">
import stui.web.*
import stui.web.parts.*
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// 個人設定: プロフィール・通知（デスクトップ・メール）・兄弟のサービスとの連携・APIキー（MCP）
case class State(name: RV[String] = RV(""),
                 tz: RV[String] = RV("Asia/Tokyo"),
                 prefs: RV[List[NotifyPrefDto]] = RV(Nil),
                 keys: RV[List[ApiKeyDto]] = RV(Nil),
                 keyName: RV[String] = RV(""),
                 scopes: RV[List[String]] = RV(List("read", "write")),
                 desktop: RV[Boolean] = RV(Prefs.get("desktop", "on") == "on"),
                 days: RV[String] = RV("90"),
                 created: RV[Option[String]] = RV(None),
                 error: RV[String] = RV(""),
                 uploading: RV[Boolean] = RV(false),
                 links: RV[List[LinkServiceDto]] = RV(Nil))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("個人設定")
  Store.me.v.foreach { m => s.name := m.name; s.tz := m.timezone }
  Api.get[List[NotifyPrefDto]]("/api/me/notify-prefs").foreach(s.prefs := _)
  loadKeys(s)
  loadLinks(s)
  // 連携の許可画面から戻ったとき（?integration=skiwi&result=connected）
  val q = new dom.URL(dom.window.location.href).searchParams
  Option(q.get("integration")).foreach { p =>
    q.get("result") match {
      case "connected" => Store.toast(s"$p と連携しました")
      case "denied"    => Store.toast(s"$p との連携は許可されませんでした", error = true)
      case _           => Store.toast(s"$p と連携できませんでした。もう一度お試しください", error = true)
    }
    dom.window.history.replaceState(null, "", "/settings")
  }
}

def loadLinks(s: State): Unit = LinkTitles.load().foreach(s.links := _)

def connect(p: String): Unit =
  dom.window.location.href = s"/api/integrations/$p/connect?returnTo=" + Api.enc(dom.window.location.origin + "/settings")

def disconnect(s: State, l: LinkServiceDto): Unit = Dialog.confirm(s"${l.name} との連携をやめますか？（${what(l.provider)}が出なくなります）", "やめる") {
  Store.report(Api.delete(s"/api/integrations/${l.provider}/connection")) { _ => Store.toast(s"${l.name} との連携をやめました"); loadLinks(s); loadKeys(s) }
}

/** 連携すると何ができるか. */
def what(p: String): String = p match {
  case "skawau" => "メッセージに貼った skawau のファイル・フォルダの URL に、あなたの skawau の権限でファイル名を表示します"
  case "skea"   => "メッセージに貼った skea のタスクの URL に、あなたの skea の権限でタスクのタイトルと状態を表示します"
  case "skaka"  => "チャンネル・DM の入力欄のカメラのボタンで、あなたとして skaka に会議室を作り、その場に案内を投稿できます"
  case "skorimako" => "メッセージに貼った skorimako のトレース・アラート・ダッシュボードの URL に、あなたの skorimako の権限で名前と状態を表示します"
  case _        => "メッセージに貼った skiwi のページの URL に、あなたの skiwi の権限でページのタイトルを表示します"
}

def setDesktop(s: State, on: Boolean): Unit = {
  s.desktop := on
  Prefs.set("desktop", if (on) "on" else "off")
  if (on) Store.askDesktopPermission()
}

/** アイコンの登録（正方形に切り取って 256px に縮めてから送る）. */
def uploadAvatar(s: State, e: dom.Event): Unit = {
  val input = e.target.asInstanceOf[dom.HTMLInputElement]
  Option(input.files).filter(_.length > 0).map(_(0)).foreach { file =>
    s.uploading := true
    val thumb = dom.window.asInstanceOf[js.Dynamic].stuiImage.squareThumb(file, 256).asInstanceOf[js.Promise[dom.File]].toFuture
    thumb.flatMap(f => Api.sendFile[Json]("/api/me/avatar", f)).onComplete { r =>
      s.uploading := false
      input.value = ""
      r match {
        case Success(_) => Store.toast("アイコンを登録しました"); Store.reloadMe()
        case Failure(e) => Store.error(e)
      }
    }
  }
}

def removeAvatar(): Unit = Dialog.confirm("アイコンの画像を外しますか？（名前の1文字目の表示に戻ります）", "外す") {
  Store.report(Api.delete("/api/me/avatar")) { _ => Store.toast("アイコンを外しました"); Store.reloadMe() }
}

def meBrief = Store.me.mapC(_.map(m => UserBrief(m.id, m.name, m.avatarUrl, false)))
def hasAvatar = Store.me.mapC(_.exists(_.avatarUrl.exists(_.startsWith("/api/users/"))))

def loadKeys(s: State): Unit = Api.get[List[ApiKeyDto]]("/api/me/api-keys").foreach(s.keys := _)

def saveProfile(s: State): Unit =
  Store.report(Api.patchUnit("/api/me", UpdateMeReq(Some(s.name.v), Some(s.tz.v)).asJson)) { _ =>
    Store.toast("保存しました")
    Api.me().foreach(m => Store.me := Some(m))
  }

def setPref(s: State, kind: String, on: Boolean): Unit = {
  s.prefs := s.prefs.v.map(p => if (p.kind == kind) p.copy(email = on) else p)
  Store.report(Api.putUnit("/api/me/notify-prefs", s.prefs.v.asJson))(_ => ())
}

def createKey(s: State): Unit = {
  s.error := ""
  Api.post[CreatedApiKeyDto]("/api/me/api-keys", CreateApiKeyReq(s.keyName.v, s.scopes.v, s.days.v.toIntOption).asJson).onComplete {
    case Success(r) => s.created := Some(r.key); s.keyName := ""; loadKeys(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def revoke(s: State, k: ApiKeyDto): Unit =
  Dialog.confirm(s"APIキー「${k.name}」を失効させますか？使っているプログラム・AI は使えなくなります。") {
    Store.report(Api.delete(s"/api/me/api-keys/${k.id}"))(_ => loadKeys(s))
  }


def origin: String = dom.window.location.origin
def mcpCommand(s: State) = s.created.mapC(k => s"claude mcp add --transport http stui $origin/mcp --header \"Authorization: Bearer ${k.getOrElse("<APIキー>")}\"")
def via(k: ApiKeyDto): String = k.client.fold("")(c => s"（$c 連携）")
</script>

<template>
  <div class="page narrow">
    <h1>個人設定</h1>
    <section class="panel">
      <h2>プロフィール</h2>
      <div class="row avatar-row">
        <Avatar :user="meBrief.v" :size="64" />
        <div>
          <label class="btn small">画像を選ぶ<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" class="hidden" @change="uploadAvatar(s, event)"></label>
          <button v-if="hasAvatar" class="ghost small" @click="removeAvatar()">外す</button>
          <span v-if="s.uploading" class="muted small">アップロード中…</span>
          <div class="muted small">PNG・JPEG・GIF・WebP（正方形に切り取って縮めます）</div>
        </div>
      </div>
      <div class="row">
        <label class="field grow"><span>表示名</span><input type="text" v-model="s.name"></label>
        <div class="field"><span>タイムゾーン</span>
          <Combo :bind="Some(s.tz)" :options='Opt.names(List("Asia/Tokyo", "UTC", "America/Los_Angeles", "America/New_York", "Europe/London", "Asia/Singapore"))' /></div>
      </div>
      <button class="primary" @click="saveProfile(s)">保存</button>
      <p class="muted small">表示名はログインのたびに IdP の情報で上書きされることがあります。</p>
    </section>

    <section class="panel">
      <h2>画面の表示</h2>
      <div class="field"><span>画面の色（この端末に記憶します）</span>
        <div class="chips">
          <button v-for="o in Theme.options" :class='Theme.current.mapC(c => if (c == o._1) "chip active" else "chip")' @click="Theme.set(o._1)">{{ o._2 }}</button>
        </div>
      </div>
    </section>

    <section class="panel">
      <h2>通知</h2>
      <label class="check"><input type="checkbox" :checked="s.desktop.v" @change="setDesktop(s, Dom.checked(event))"> 画面を開いていないとき、デスクトップに通知を出す（この端末）</label>
      <p class="muted small">チャンネルごとの通知（すべて・メンションだけ・通知しない・ミュート）は、各チャンネルの ⚙ から変えられます。
        メールは、通知を受けてから少し待っても画面で読まれなかったときだけ送ります。メールでも受け取る種類を選んでください。</p>
      <div class="chips">
        <label v-for="p in s.prefs" class="chip"><input type="checkbox" :checked="p.email" @change="setPref(s, p.kind, Dom.checked(event))"> {{ NotificationKind.label(p.kind) }}</label>
      </div>
    </section>

    <section v-for="l in s.links" class="panel">
      <h2>{{ l.name }} 連携</h2>
      <p class="muted small">{{ what(l.provider) }}（<code>{{ l.url }}</code>）。</p>
      <div class="row">
        <span v-if="l.connected" class="badge cat-done">連携しています</span>
        <span v-else class="badge">連携していません</span>
        <span class="grow"></span>
        <button v-if="l.connected" @click="disconnect(s, l)">連携をやめる</button>
        <button v-else class="primary" @click="connect(l.provider)">{{ l.name }} と連携する</button>
      </div>
    </section>

    <section class="panel">
      <h2>APIキー（REST API・MCP）</h2>
      <p class="muted small">プログラムや AI（MCP）から stui を使うためのキーです。<code>Authorization: Bearer &lt;キー&gt;</code> で送ります。
        権限は「あなたの権限」と「キーのスコープ」の両方を満たす範囲です。</p>
      <form @submit.prevent="createKey(s)">
        <div class="row">
          <input type="text" v-model="s.keyName" placeholder="用途（例: Claude Code）" class="grow">
          <Combo :bind="Some(s.days)" :options='Opt.of(List("30" -> "30日", "90" -> "90日", "365" -> "1年", "" -> "無期限"))' />
        </div>
        <div class="row scopes">
          <span class="small muted">スコープ:</span>
          <label class="small"><input type="checkbox" value="read" v-model="s.scopes"> 参照（read）</label>
          <label class="small"><input type="checkbox" value="write" v-model="s.scopes"> 作成・更新・削除（write）</label>
          <label v-if="Store.isAdmin" class="small"><input type="checkbox" value="admin" v-model="s.scopes"> 管理（admin）</label>
        </div>
        <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
        <button type="submit" class="primary">発行</button>
      </form>
      <div v-if="s.created.mapC(_.isDefined)" class="created">
        <p><b>APIキーを発行しました。</b>この画面を離れると二度と表示できません。安全な場所に保存してください。</p>
        <div class="row"><code class="grow keytext">{{ s.created.mapC(_.getOrElse("")) }}</code><button class="small" @click="Dom.copy(s.created.v.getOrElse(&quot;&quot;))">コピー</button></div>
        <p class="small">Claude Code から使う（MCP）:</p>
        <div class="row"><code class="grow keytext">{{ mcpCommand(s) }}</code><button class="small" @click="Dom.copy(mcpCommand(s).v)">コピー</button></div>
        <button class="small" @click="s.created := None">閉じる</button>
      </div>
      <div class="table-wrap keys">
        <table class="list">
          <thead><tr><th>用途</th><th>キー</th><th>スコープ</th><th>有効期限</th><th>最終使用</th><th></th></tr></thead>
          <tbody>
            <tr v-for="k in s.keys" :key="k.id">
              <td>{{ k.name }} <span class="muted small">{{ via(k) }}</span></td><td><code>{{ k.prefix }}…</code></td><td>{{ k.scopes.mkString(", ") }}</td>
              <td class="small">{{ k.expiresAt.map(Fmt.dateTime).getOrElse("無期限") }}</td>
              <td class="small">{{ k.lastUsedAt.map(Fmt.ago).getOrElse("未使用") }}</td>
              <td><button class="danger small" @click="revoke(s, k)">失効</button></td>
            </tr>
            <tr v-if="s.keys.mapC(_.isEmpty)"><td colspan="6" class="muted">発行済みの APIキーはありません</td></tr>
          </tbody>
        </table>
      </div>
      <p class="small muted">MCP のエンドポイント: <code>{{ origin }}/mcp</code>（Streamable HTTP）。REST API の一覧は <a href="/api/endpoints" target="_blank">/api/endpoints</a>。</p>
    </section>
  </div>
</template>

<style scoped>
.avatar-row { margin-bottom: .8rem; gap: 1rem; }
.scopes { margin: .5rem 0; }
.created { border: 1px solid var(--ok); border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; background: color-mix(in srgb, var(--ok) 8%, transparent); }
.keytext { background: var(--side); padding: .3rem .5rem; border-radius: 5px; word-break: break-all; font-size: .8rem; }
.keys { margin-top: 1rem; }
</style>
