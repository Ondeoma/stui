<script lang="scala">
import stui.web.*
import stui.shared.*

import java.util.UUID
import scala.scalajs.js

// リマインダーを作る（20分後・1時間後・3時間後・明日の朝・来週の月曜・日時を指定）. その時刻に stui から DM で知らせる.
case class Props(messageId: Option[UUID] = None, onClose: Unit => Unit = _ => (), onSaved: Unit => Unit = _ => ())

case class State(text: RV[String] = RV(""), date: RV[String] = RV(Fmt.today()), time: RV[String] = RV("09:00"))

def initState() = State()

def inMinutes(n: Int): js.Date = new js.Date(js.Date.now() + n * 60000.0)

/** 次の日（または n 日後）の朝9時. */
def morning(days: Int): js.Date = { val d = new js.Date(); d.setDate(d.getDate() + days); d.setHours(9, 0, 0, 0); d }

def nextMonday(): js.Date = { val d = new js.Date(); val add = ((8 - d.getDay().toInt) % 7) match { case 0 => 7; case x => x }; d.setDate(d.getDate() + add); d.setHours(9, 0, 0, 0); d }

def save(s: State, props: Props, at: js.Date): Unit =
  if (props.messageId.isEmpty && s.text.v.trim.isEmpty) Store.toast("内容を入力してください", error = true)
  else Store.report(Api.remind(ReminderReq(Option(s.text.v.trim).filter(_.nonEmpty), props.messageId, Timestamp(at.toISOString())))) { _ =>
    Store.toast(s"${Fmt.dateTime(Timestamp(at.toISOString()))} にリマインドします")
    props.onSaved(())
    props.onClose(())
  }

def custom(s: State, props: Props): Unit = save(s, props, new js.Date(s"${s.date.v}T${s.time.v}:00"))
</script>

<template>
  <Modal :onClose="props.onClose">
    <h2>⏰ リマインダー</h2>
    <label class="field">内容{{ if (props.messageId.isDefined) "（任意）" else "" }}
      <input type="text" v-model="s.text" :placeholder='if (props.messageId.isDefined) "このメッセージについて" else "例: 資料を送る"' maxlength="1000"></label>
    <div class="presets">
      <button type="button" @click="save(s, props, inMinutes(20))">20分後</button>
      <button type="button" @click="save(s, props, inMinutes(60))">1時間後</button>
      <button type="button" @click="save(s, props, inMinutes(180))">3時間後</button>
      <button type="button" @click="save(s, props, morning(1))">明日の朝（9:00）</button>
      <button type="button" @click="save(s, props, nextMonday())">来週の月曜（9:00）</button>
    </div>
    <form class="row custom" @submit.prevent="custom(s, props)">
      <input type="date" v-model="s.date"><input type="time" v-model="s.time">
      <button type="submit" class="primary">この日時に</button>
    </form>
    <div class="actions"><button type="button" @click="props.onClose(())">閉じる</button></div>
  </Modal>
</template>

<style scoped>
h2 { margin: 0 0 1rem; font-size: 1.15rem; }
.presets { display: flex; flex-wrap: wrap; gap: .4rem; margin: .6rem 0; }
.custom { gap: .4rem; margin-top: .4rem; }
</style>
