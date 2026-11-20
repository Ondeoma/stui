<script lang="scala">
import stui.web.*
import stui.shared.*


// 左のサイドバー: スレッド・アクティビティ・保存・下書き、お気に入り、チャンネル、DM
case class State(showChannels: RV[Boolean] = RV(Prefs.get("side.channels", "1") == "1"),
                 showDms: RV[Boolean] = RV(Prefs.get("side.dms", "1") == "1"),
                 showFav: RV[Boolean] = RV(Prefs.get("side.fav", "1") == "1"))

def initState() = State()

def toggle(r: RV[Boolean], key: String): Unit = { r := !r.v; Prefs.set(key, if (r.v) "1" else "0") }

def favorites = Store.channels.mapC(_.filter(_.favorite).sortBy(c => Store.title(c).toLowerCase))
def chans = Store.channels.mapC(_.filter(c => !c.favorite && !ChannelKind.isDm(c.kind)).sortBy(_.name.toLowerCase))
def dms = Store.channels.mapC(_.filter(c => !c.favorite && ChannelKind.isDm(c.kind))
  .sortBy(c => -c.lastMessageAt.map(_.toEpochMilli).getOrElse(c.createdAt.toEpochMilli)).take(40))

def cls(c: ChannelDto) = Router.href.mapC { h =>
  val active = new org.scalajs.dom.URL(h).pathname.startsWith(s"/c/${c.id}")
  List("item", if (active) "active" else "", if (c.unread > 0 && !c.muted) "unread" else "", if (c.muted) "muted" else "").filter(_.nonEmpty).mkString(" ")
}
def navCls(path: String) = Router.href.mapC(h => if (new org.scalajs.dom.URL(h).pathname == path) "item active" else "item")
def icon(c: ChannelDto): String = ChannelKind.icon(c.kind)
def isDm(c: ChannelDto): Boolean = c.kind == ChannelKind.Dm
def threads = Store.threadsUnread.mapC(n => if (n > 0) n.toString else "")
def saved = Store.savedCount.mapC(n => if (n > 0) n.toString else "")
def mentionsOf(c: ChannelDto): String = if (c.mentions > 99) "99+" else c.mentions.toString
def go(c: ChannelDto): Unit = Store.openChannel(c.id)
def nav(path: String): Unit = { Store.sideOpen := false; Router.go(path) }
def caret(r: RV[Boolean]) = r.mapC(o => if (o) "▾" else "▸")
</script>

<template>
  <div class="head">
    <a href="/" class="brand" @click.prevent='nav("/")'><img :src='AppNames.logo("/logo.svg").v' alt="" height="26" class="brand-logo"><span>{{ AppNames.self }}</span></a>
  </div>
  <nav class="scroll">
    <a href="/threads" :class='navCls("/threads")' @click.prevent='nav("/threads")'><span class="ic"><span class="ico ico-thread"></span></span><span class="name">スレッド</span>
      <span v-if="threads.mapC(_.nonEmpty)" class="badge soft">{{ threads }}</span></a>
    <a href="/activity" :class='navCls("/activity")' @click.prevent='nav("/activity")'><span class="ic">🔔</span><span class="name">アクティビティ</span>
      <span v-if="Store.unread.mapC(_.mentions > 0)" class="badge">{{ Store.unread.mapC(_.mentions.toString) }}</span></a>
    <a href="/saved" :class='navCls("/saved")' @click.prevent='nav("/saved")'><span class="ic">🔖</span><span class="name">保存済み</span>
      <span v-if="saved.mapC(_.nonEmpty)" class="badge soft">{{ saved }}</span></a>
    <a href="/drafts" :class='navCls("/drafts")' @click.prevent='nav("/drafts")'><span class="ic">✏️</span><span class="name">下書き</span></a>
    <a href="/reminders" :class='navCls("/reminders")' @click.prevent='nav("/reminders")'><span class="ic">⏰</span><span class="name">リマインダー</span></a>

    <template v-if="favorites.mapC(_.nonEmpty)">
      <div class="sec"><button class="sec-btn" @click='toggle(s.showFav, "side.fav")'>{{ caret(s.showFav) }} お気に入り</button></div>
      <template v-if="s.showFav">
        <a v-for="c in favorites" :key="c.id" :href='"/c/" + c.id' :class="cls(c)" @click.prevent="go(c)">
          <span v-if="isDm(c)" class="ic"><Avatar :user="c.dmUsers.headOption.map(person)" :size="18" /></span>
          <span v-else class="ic">{{ icon(c) }}</span>
          <span class="name">{{ Store.title(c) }}</span>
          <span v-if="c.mentions > 0" class="badge">{{ mentionsOf(c) }}</span>
        </a>
      </template>
    </template>

    <div class="sec"><button class="sec-btn" @click='toggle(s.showChannels, "side.channels")'>{{ caret(s.showChannels) }} チャンネル</button>
      <button class="add" title="チャンネルを作る" @click='Store.dialog := "channel"'>＋</button></div>
    <template v-if="s.showChannels">
      <a v-for="c in chans" :key="c.id" :href='"/c/" + c.id' :class="cls(c)" @click.prevent="go(c)">
        <span class="ic">{{ icon(c) }}</span><span class="name">{{ c.name }}</span>
        <span v-if="c.mentions > 0" class="badge">{{ mentionsOf(c) }}</span>
      </a>
      <a href="/browse" class="item sub" @click.prevent='nav("/browse")'><span class="ic">🔍</span><span class="name">チャンネルを探す</span></a>
    </template>

    <div class="sec"><button class="sec-btn" @click='toggle(s.showDms, "side.dms")'>{{ caret(s.showDms) }} ダイレクトメッセージ</button>
      <button class="add" title="DM を始める" @click='Store.dialog := "dm"'>＋</button></div>
    <template v-if="s.showDms">
      <a v-for="c in dms" :key="c.id" :href='"/c/" + c.id' :class="cls(c)" @click.prevent="go(c)">
        <span v-if="isDm(c)" class="ic"><Avatar :user="c.dmUsers.headOption.map(person)" :size="18" /></span>
        <span v-else class="ic">{{ icon(c) }}</span>
        <span class="name">{{ Store.title(c) }}</span>
        <span v-if="c.dmUsers.exists(_.bot)" class="app">APP</span>
        <span v-if="c.mentions > 0" class="badge">{{ mentionsOf(c) }}</span>
      </a>
    </template>
  </nav>
</template>

<style scoped>
.head { height: var(--header); display: flex; align-items: center; padding: 0 1rem; border-bottom: 1px solid var(--side-line); flex: none; }
.brand { display: flex; align-items: center; gap: .5rem; font-weight: 800; font-size: 1.15rem; color: var(--side-fg); letter-spacing: .02em; }
.brand:hover { text-decoration: none; }
.brand img { border-radius: 7px; width: auto; max-width: 120px; }
.scroll { flex: 1; overflow-y: auto; padding: .5rem .45rem 2rem; min-height: 0; }
.item { display: flex; align-items: center; gap: .45rem; padding: .28rem .55rem; border-radius: 7px; color: var(--side-muted); font-size: .92rem;
  line-height: 1.35; }
.item:hover { background: var(--side-hover); text-decoration: none; color: var(--side-fg); }
.item.active { background: var(--side-active); color: #fff; }
.item.unread { color: var(--side-fg); font-weight: 700; }
.item.muted { opacity: .55; }
.item.sub { font-size: .85rem; }
.ic { width: 1.2rem; text-align: center; flex: none; display: inline-flex; justify-content: center; font-size: .9rem; }
.name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { background: var(--danger); color: #fff; border-radius: 999px; font-size: .7rem; padding: 0 .45rem; font-weight: 700; line-height: 1.5; }
.badge.soft { background: var(--side-hover); color: var(--side-fg); }
.app { font-size: .6rem; border: 1px solid var(--side-muted); border-radius: 4px; padding: 0 .25rem; }
.sec { display: flex; align-items: center; margin-top: 1rem; padding: 0 .2rem; }
.sec-btn { flex: 1; border: none; background: none; color: var(--side-muted); font-size: .8rem; font-weight: 700; padding: .25rem .3rem; text-align: left;
  justify-content: flex-start; }
.sec-btn:hover { color: var(--side-fg); }
.add { border: none; background: none; color: var(--side-muted); font-size: 1rem; padding: 0 .4rem; }
.add:hover { color: var(--side-fg); background: var(--side-hover); }
</style>
