<script lang="scala">
import stui.web.*
import stui.web.parts.*
import io.circe.syntax.*
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// リマインダーと、自分が作った定期メッセージ（送信の予約）
case class State(tab: RV[String] = RV("reminders"), reminders: RV[List[ReminderDto]] = RV(Nil), done: RV[Boolean] = RV(false),
                 scheduled: RV[List[ScheduledDto]] = RV(Nil), add: RV[Boolean] = RV(false), edit: RV[Option[ScheduledDto]] = RV(None))

def initState() = State()

def mounted(s: State): Unit = { pageTitle("リマインダー"); load(s) }

def load(s: State): Unit = {
  Api.reminders(s.done.v).foreach(s.reminders := _)
  Api.scheduled(None).foreach(s.scheduled := _)
}

def complete(s: State, r: ReminderDto): Unit =
  Store.report(Api.patchUnit(s"/api/reminders/${r.id}", ReminderPatch(None, Some(!r.done)).asJson.dropNullValues))(_ => load(s))

def snooze(s: State, r: ReminderDto, minutes: Int): Unit =
  Store.report(Api.patchUnit(s"/api/reminders/${r.id}", ReminderPatch(Some(Timestamp(new js.Date(js.Date.now() + minutes * 60000.0).toISOString())), None)
    .asJson.dropNullValues)) { _ => Store.toast("あとで知らせます"); load(s) }

def remove(s: State, r: ReminderDto): Unit = Store.report(Api.delete(s"/api/reminders/${r.id}"))(_ => load(s))

def removeSched(s: State, x: ScheduledDto): Unit = Dialog.confirm("この定期メッセージを削除しますか？", "削除する") {
  Store.report(Api.delete(s"/api/scheduled/${x.id}"))(_ => load(s))
}

def openRef(r: ReminderDto): Unit = r.message.foreach(m => Router.go(s"/m/${m.id}"))


def tabCls(s: State, t: String) = s.tab.mapC(x => if (x == t) "tab active" else "tab")
def rowCls(r: ReminderDto): String = if (r.done) "rem done" else if (r.fired) "rem fired" else "rem"
</script>

<template>
  <div class="page scroll-page">
    <h1>⏰ リマインダー</h1>
    <nav class="tabs">
      <button :class='tabCls(s, "reminders")' @click='s.tab := "reminders"'>リマインダー</button>
      <button :class='tabCls(s, "scheduled")' @click='s.tab := "scheduled"'>定期メッセージ・送信の予約</button>
    </nav>
    <div v-if='s.tab.mapC(_ == "reminders")'>
      <div class="row bar"><button class="primary small" @click="s.add := true">＋ リマインダー</button><span class="grow"></span>
        <label class="check"><input type="checkbox" v-model="s.done" @change="load(s)"> 完了したものも出す</label></div>
      <p v-if="s.reminders.mapC(_.isEmpty)" class="muted">リマインダーはありません。メッセージの「⋯」→「リマインド」でも作れます。</p>
      <div v-for="r in s.reminders" :key="r.id" :class="rowCls(r)">
        <div class="grow">
          <div><b>{{ Fmt.dateTime(r.remindAt) }}</b> <span v-if="r.fired && !r.done" class="badge">お知らせ済み</span></div>
          <div v-if="r.text.nonEmpty">{{ r.text }}</div>
          <div v-for="m in r.message.toList" class="ref muted small" @click="openRef(r)">
            {{ Store.prefix(m.channel.kind) }}{{ Store.titleOf(m.channel) }}・{{ m.author.map(_.name).getOrElse("") }}: {{ m.text }}</div>
        </div>
        <button v-if="!r.done" class="small ghost" @click="snooze(s, r, 60)">1時間後</button>
        <button class="small ghost" @click="complete(s, r)">{{ if (r.done) "戻す" else "完了" }}</button>
        <button class="small ghost" @click="remove(s, r)">削除</button>
      </div>
    </div>
    <div v-if='s.tab.mapC(_ == "scheduled")'>
      <p class="muted small">定期メッセージは各チャンネルの「⚙ 設定」→「連携・定期メッセージ」、送信の予約は入力欄の ⏱ から作れます。</p>
      <p v-if="s.scheduled.mapC(_.isEmpty)" class="muted">定期メッセージはありません。</p>
      <div v-for="x in s.scheduled" :key="x.id" class="rem">
        <div class="grow">
          <div><b>{{ Fmt.schedule(x) }}</b> {{ Store.prefix(x.channel.kind) }}{{ Store.titleOf(x.channel) }}
            <span v-if="!x.enabled" class="badge">停止中</span></div>
          <div class="rich muted" v-html="x.html"></div>
          <div class="muted small">次回: {{ x.nextRun.map(Fmt.dateTime).getOrElse("—") }}{{ x.lastError.fold("")(e => "・前回の失敗: " + e) }}</div>
        </div>
        <button class="small ghost" @click="s.edit := Some(x)">変える</button>
        <button class="small ghost" @click="removeSched(s, x)">削除</button>
      </div>
    </div>
  </div>
  <ReminderDialog v-if="s.add" :onClose="_ => s.add := false" :onSaved="_ => load(s)" />
  <ScheduleDialog v-for="x in s.edit.mapC(_.toList)" :channelId="x.channel.id" :editing="Some(x)" :onClose="_ => s.edit := None" :onSaved="_ => load(s)" />
</template>

<style scoped>
.bar { margin-bottom: .8rem; }
.rem { display: flex; gap: .5rem; align-items: flex-start; padding: .75rem .8rem; border: 1px solid var(--line); border-radius: 8px; margin-bottom: .6rem; }
.rem .grow > div + div { margin-top: .25rem; }
.rem.done { opacity: .55; }
.rem.fired { background: var(--accent-weak); }
.ref { cursor: pointer; margin-top: .2rem; }
.ref:hover { text-decoration: underline; }
.badge { font-size: .7rem; border: 1px solid var(--line); border-radius: 999px; padding: 0 .4rem; color: var(--muted); }
</style>
