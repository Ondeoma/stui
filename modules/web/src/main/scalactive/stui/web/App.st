<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import stui.shared.*
import stui.web.parts.*
import stui.web.views.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 画面全体の枠: 左のサイドバー（チャンネル・DM）と、上の検索・通知・メニュー、ルートごとの画面
case class State(ready: RV[Boolean] = RV(false),
                 menu: RV[Boolean] = RV(false),
                 q: RV[String] = RV(""))

def initState() = State()

val routes: PathPatternRoutes = List(
  ("/", _ => HomeView()),
  ("/login", _ => LoginView()),
  ("/c/:id", r => ChannelView(channelId = UUID.fromString(r.pathname.groups("id")), threadRoot = None)),
  ("/c/:id/t/:root", r => ChannelView(channelId = UUID.fromString(r.pathname.groups("id")), threadRoot = Some(UUID.fromString(r.pathname.groups("root"))))),
  ("/m/:id", r => PermalinkView(messageId = UUID.fromString(r.pathname.groups("id")))),
  ("/threads", _ => ThreadsView()),
  ("/activity", _ => NotificationView()),
  ("/saved", _ => SavedView()),
  ("/reminders", _ => RemindersView()),
  ("/drafts", _ => DraftsView()),
  ("/browse", _ => BrowseView()),
  ("/search", _ => SearchView()),
  ("/settings", _ => MySettingsView()),
  ("/admin", _ => AdminView()),
)

def mounted(s: State): Unit = {
  Fallback.install(routes)
  Api.clientConfig().foreach { c =>
    Store.config := Some(c)
    // skiwi・skawau・skea・skorimako と連携していれば、そのリンクに名前を出す
    if (c.linkServices.nonEmpty) loadLinkTitles()
  }
  if (dom.window.location.pathname.startsWith("/login")) s.ready := true
  else Toasts.report(Api.me()) { me =>
    Store.me := Some(me)
    AppNames.set(me.appNames)
    Observe.setUser(me.id.toString)
    // 自分へのメンションを目立たせる
    val st = dom.document.createElement("style")
    st.textContent = s""".rich .mention[data-user-id="${me.id}"] { background: rgba(250, 204, 21, .4); color: inherit; }"""
    dom.document.head.appendChild(st)
    Store.loadChannels().onComplete { _ =>
      Store.poll()
      Store.connect()
      Voice.reload()
      s.ready := true
    }
    Emojis.load()
  }
  // Ctrl+K で検索. ボイスチャンネルに入っている間は Ctrl+Shift+M でマイク、Ctrl+Shift+D でスピーカー
  useEventListener[dom.KeyboardEvent](dom.document, "keydown") { e =>
    if ((e.ctrlKey || e.metaKey) && e.key == "k") {
      e.preventDefault()
      Option(dom.document.querySelector(".top .search input")).foreach(_.asInstanceOf[dom.HTMLInputElement].focus())
    } else if ((e.ctrlKey || e.metaKey) && e.shiftKey && Voice.channel.v.isDefined && e.key.toLowerCase == "m") {
      e.preventDefault(); Voice.toggleMute()
    } else if ((e.ctrlKey || e.metaKey) && e.shiftKey && Voice.channel.v.isDefined && e.key.toLowerCase == "d") {
      e.preventDefault(); Voice.toggleDeafen()
    }
  }
  // 画面を閉じたらボイスチャンネルから出る
  useEventListener[dom.Event](dom.window, "pagehide")(_ => Voice.leaveNow())
  ()
}

def search(s: State): Unit = {
  val q = s.q.v.trim
  if (q.nonEmpty) Router.go("/search?q=" + Api.enc(q))
}

def logout(): Unit = Api.logout().onComplete(r => dom.window.location.href = r.getOrElse("/"))

def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def meBrief = Store.me.mapC(_.map(m => Person(m.id.toString, m.name, m.avatarUrl)))
def unreadLabel = Store.unread.mapC(u => if (u.unread > 99) "99+" else u.unread.toString)
def hasUnread = Store.unread.mapC(_.unread > 0)
def loggedIn = Store.me.mapC(_.isDefined)
def sideClass = Store.sideOpen.mapC(o => if (o) "side open" else "side")
def inVoice = Voice.channel.mapC(_.isDefined)
def openVoice(): Unit = Voice.channel.v.foreach(Store.openChannel)
</script>

<template>
  <div class="shell">
    <aside v-if="loggedIn" :class="sideClass">
      <Sidebar />
    </aside>
    <div v-if="Store.sideOpen" class="side-bg" @click="Store.sideOpen := false"></div>
    <div class="main">
      <header v-if="loggedIn" class="top">
        <button class="ghost icon only-sp" title="チャンネル" @click="Store.sideOpen := !Store.sideOpen.v">☰</button>
        <form class="search" @submit.prevent="search(s)">
          <input type="search" v-model="s.q" placeholder="検索（例: 障害 in:#開発 from:@山田）  Ctrl+K">
        </form>
        <button v-if="inVoice" class="ghost icon voice-ind" title="つながっているボイスチャンネルを開く" @click="openVoice()">🔊</button>
        <RouterLink to="/activity" class="icon bell">
          🔔<span v-if="hasUnread" class="dot">{{ unreadLabel }}</span>
        </RouterLink>
        <div class="me">
          <button class="ghost me-btn" @click="s.menu := !s.menu.v"><Avatar :user="meBrief.v" :size="26" /><span class="hide-sp">{{ userName }}</span> ▾</button>
          <div v-if="s.menu" class="menu-bg" @click="s.menu := false"></div>
          <div v-if="s.menu" class="menu" @click="s.menu := false">
            <RouterLink to="/settings">個人設定・連携・APIキー</RouterLink>
            <RouterLink to="/reminders">リマインダー・定期メッセージ</RouterLink>
            <RouterLink v-if="Store.isAdmin" to="/admin">管理</RouterLink>
            <a href="#" @click.prevent="logout()">ログアウト</a>
          </div>
        </div>
      </header>
      <main class="content">
        <RouterComponent v-if="s.ready" :routes="routes" />
      </main>
    </div>
  </div>
  <CreateChannelDialog v-if='Store.dialog.mapC(d => d == "channel" || d == "voice")' />
  <NewDmDialog v-if='Store.dialog.mapC(_ == "dm")' />
  <DialogHost />
  <ToastHost />
</template>

<style scoped>
.shell { display: flex; height: 100vh; height: 100dvh; overflow: hidden; }
.side { width: 260px; flex: none; background: var(--side-bg); color: var(--side-fg); display: flex; flex-direction: column; min-height: 0; }
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
.content { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.top { height: var(--header); flex: none; display: flex; align-items: center; gap: .6rem; padding: 0 .9rem; background: var(--bg);
  border-bottom: 1px solid var(--line); }
.search { flex: 1; display: flex; justify-content: center; }
.search input { width: 100%; max-width: 560px; }
.icon { font-size: 1.1rem; padding: .2rem .4rem; border-radius: 7px; color: var(--fg); position: relative; }
.icon:hover { background: var(--hover); text-decoration: none; }
.dot { position: absolute; top: -4px; right: -6px; background: var(--danger); color: #fff; border-radius: 999px; font-size: .62rem; padding: 0 .35rem; line-height: 1.4; }
.me { position: relative; }
.menu { position: absolute; right: 0; top: 2.4rem; background: var(--bg); border: 1px solid var(--line); border-radius: 9px; box-shadow: var(--shadow);
  min-width: 230px; padding: .3rem; display: flex; flex-direction: column; z-index: 200; }
.menu-bg { position: fixed; inset: 0; z-index: 199; }
.side-bg { display: none; }
.voice-ind { color: #22c55e; }
@media (max-width: 760px) {
  .side { position: fixed; top: 0; bottom: 0; left: 0; z-index: 300; transform: translateX(-100%); transition: transform .18s ease-out; width: 82vw; max-width: 300px; }
  .side.open { transform: none; box-shadow: var(--shadow); }
  .side-bg { display: block; position: fixed; inset: 0; background: rgba(0, 0, 0, .45); z-index: 250; }
  .top { padding: 0 .5rem; gap: .3rem; }
}
</style>
