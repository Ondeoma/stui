<script lang="scala">
import stui.web.*
import stui.web.parts.*
import org.scalajs.dom
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 検索: メッセージ（日本語の部分一致・かなの揺れ・タイプミスを許す. 絞り込みの構文）・チャンネル・人・ファイル
case class State(q: RV[String] = RV(""), result: RV[Option[SearchResultDto]] = RV(None), loading: RV[Boolean] = RV(false), more: RV[Boolean] = RV(false),
                 order: RV[String] = RV(""), help: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State): Unit = {
  pageTitle("検索")
  val q = Option(new dom.URL(dom.window.location.href).searchParams.get("q")).getOrElse("")
  s.q := q
  if (q.trim.nonEmpty) run(s)
}

def fullQuery(s: State): String = (s.q.v.trim + (if (s.order.v.nonEmpty) s" order:${s.order.v}" else "")).trim

def run(s: State): Unit = {
  s.loading := true
  dom.window.history.replaceState(null, "", "/search?q=" + Api.enc(s.q.v.trim))
  Api.search(fullQuery(s)).foreach { r => s.result := Some(r); s.loading := false; s.more := r.hits.size < r.total }
}

def loadMore(s: State): Unit = s.result.v.foreach { r =>
  Api.search(fullQuery(s), offset = r.hits.size).foreach { n =>
    val all = r.hits ++ n.hits.filterNot(h => r.hits.exists(_.message.id == h.message.id))
    s.result := Some(r.copy(hits = all)); s.more := all.size < r.total && n.hits.nonEmpty
  }
}

def add(s: State, f: String): Unit = { s.q := (s.q.v.trim + " " + f).trim; run(s) }

def hits(s: State) = s.result.mapC(_.map(_.hits).getOrElse(Nil))
def channels(s: State) = s.result.mapC(_.map(_.channels).getOrElse(Nil))
def users(s: State) = s.result.mapC(_.map(_.users).getOrElse(Nil))
def files(s: State) = s.result.mapC(_.map(_.files).getOrElse(Nil))
def error(s: State) = s.result.mapC(_.flatMap(_.error).getOrElse(""))
def total(s: State) = s.result.mapC(_.map(r => s"${r.total} 件").getOrElse(""))
def filters: List[(String, String)] = List("in:#" -> "チャンネル", "from:@" -> "送信者", "from:me" -> "自分", "with:@" -> "メンション", "has:link" -> "リンク",
  "has:file" -> "ファイル", "has:image" -> "画像", "is:thread" -> "スレッド", "is:pinned" -> "ピン留め", "is:saved" -> "保存", "after:-7d" -> "1週間以内",
  "during:" + Fmt.today().take(7) -> "今月")
</script>

<template>
  <div class="page scroll-page">
    <form class="row bar" @submit.prevent="run(s)">
      <input type="search" class="grow" v-model="s.q" placeholder="検索（例: 障害 in:#開発 from:@山田 after:-7d）" autofocus>
      <select v-model="s.order" @change="run(s)"><option value="">関連度順</option><option value="new">新しい順</option><option value="old">古い順</option></select>
      <button class="primary" type="submit">検索</button>
      <button class="ghost" type="button" @click="s.help := !s.help.v">？</button>
    </form>
    <div class="chips"><button v-for="f in filters" class="chip" @click="add(s, f._1)">{{ f._2 }}</button></div>
    <div v-if="s.help" class="help muted small">
      <p>語を空白で区切るとすべてを含むもの、<code>-語</code> で除外、<code>"語句"</code> で語句のまま。ひらがな・カタカナ・全角半角の違いや、少しの打ち間違いは区別しません。</p>
      <p><code>in:#チャンネル</code> <code>in:@名前</code>（DM） <code>from:@名前</code> <code>from:me</code> <code>with:@名前</code>（メンション）
        <code>has:link|file|image|reaction|pin|meeting|thread</code> <code>is:thread|reply|pinned|saved|bot|dm|unread</code>
        <code>before:2026-11-01</code> <code>after:-7d</code> <code>on:today</code> <code>during:2026-11</code> <code>order:new|old</code></p>
    </div>
    <p v-if="s.loading" class="muted">検索中…</p>
    <p v-if="error(s).mapC(_.nonEmpty)" class="error">{{ error(s) }}</p>
    <div v-if="channels(s).mapC(_.nonEmpty)" class="section">
      <h3>チャンネル</h3>
      <div class="cards">
        <button v-for="c in channels(s)" :key="c.id" class="card-btn" @click="Store.openChannel(c.id)">{{ Store.prefix(c.kind) }}{{ c.name }}</button>
      </div>
    </div>
    <div v-if="users(s).mapC(_.nonEmpty)" class="section">
      <h3>人</h3>
      <div class="cards">
        <button v-for="u in users(s)" :key="u.id" class="card-btn" @click="Store.openDm(List(u.id))"><Avatar :user="Some(u)" :size="20" /> {{ u.name }}</button>
      </div>
    </div>
    <div v-if="files(s).mapC(_.nonEmpty)" class="section">
      <h3>ファイル</h3>
      <div v-for="f in files(s)" :key="f.attachment.id" class="file">
        <a :href="f.attachment.url" target="_blank" rel="noopener">📎 {{ f.attachment.fileName }}</a>
        <span class="muted small">{{ Store.prefix(f.channel.kind) }}{{ Store.titleOf(f.channel) }}・{{ f.createdBy.map(_.name).getOrElse("") }}・{{ Fmt.ago(f.createdAt) }}</span>
      </div>
    </div>
    <div v-if="s.result.mapC(_.isDefined)" class="section">
      <h3>メッセージ <span class="muted small">{{ total(s) }}</span></h3>
      <div v-for="h in hits(s)" :key="h.message.id">
        <ResultItem :m="h.message" :channel="h.channel" :snippet="h.snippet" :extra='if (h.fuzzy) "似ている語" else ""' />
      </div>
      <button v-if="s.more" class="small" @click="loadMore(s)">さらに表示</button>
    </div>
  </div>
</template>

<style scoped>
.bar { gap: .5rem; margin-bottom: .6rem; }
.chips { display: flex; gap: .3rem; flex-wrap: wrap; margin-bottom: .8rem; }
.chip { border-radius: 999px; padding: .15rem .7rem; font-size: .8rem; }
.help { background: var(--side); border-radius: 8px; padding: .5rem .8rem; margin-bottom: .8rem; }
.section { margin-bottom: 1.2rem; }
h3 { font-size: .95rem; margin: .4rem 0 .5rem; }
.cards { display: flex; gap: .4rem; flex-wrap: wrap; }
.card-btn { display: inline-flex; gap: .35rem; align-items: center; }
.file { display: flex; gap: .6rem; align-items: baseline; padding: .3rem 0; flex-wrap: wrap; }
</style>
