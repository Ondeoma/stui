<script lang="scala">
import stui.web.*
import io.circe.Json
import stui.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 定期メッセージ・送信の予約（1回だけ・毎日・平日・毎週・毎月の決まった時刻に投稿する）
case class Props(channelId: UUID, rootId: Option[UUID] = None, body: Option[Json] = None, editing: Option[ScheduledDto] = None,
                 onClose: Unit => Unit = _ => (), onSaved: Unit => Unit = _ => ())

case class State(freq: RV[String], date: RV[String], time: RV[String], weekday: RV[String], monthday: RV[String], enabled: RV[Boolean],
                 editor: RV[Option[EditorHandle]] = RV(None), busy: RV[Boolean] = RV(false))

def initState(props: Props) = props.editing match {
  case Some(e) => State(RV(e.frequency), RV(e.runDate.getOrElse(Fmt.today())), RV(e.runTime), RV(e.weekday.getOrElse(1).toString),
    RV(e.monthday.getOrElse(1).toString), RV(e.enabled))
  case None => State(RV(Frequency.Once), RV(Fmt.addDays(Fmt.today(), 1)), RV("09:00"), RV("1"), RV("1"), RV(true))
}

def content(props: Props): Option[Json] = props.editing.map(_.body).orElse(props.body)

def save(s: State, props: Props): Unit = s.editor.v.foreach { ed =>
  if (!s.busy.v) {
    s.busy := true
    val r = ScheduledReq(props.editing.map(_.channel.id).getOrElse(props.channelId), Editor.fromJs(ed.getJSON()), s.freq.v,
      Option.when(s.freq.v == Frequency.Once)(s.date.v), s.time.v, Option.when(s.freq.v == Frequency.Weekly)(s.weekday.v.toInt),
      Option.when(s.freq.v == Frequency.Monthly)(s.monthday.v.toIntOption.getOrElse(1)), s.enabled.v, props.editing.flatMap(_.rootId).orElse(props.rootId))
    val f = props.editing match {
      case Some(e) => Api.putUnit(s"/api/scheduled/${e.id}", io.circe.syntax.EncoderOps(r).asJson)
      case None    => Api.post[List[ScheduledDto]]("/api/scheduled", io.circe.syntax.EncoderOps(r).asJson).map(_ => ())
    }
    f.onComplete { res =>
      s.busy := false
      res match {
        case scala.util.Success(_) =>
          Store.toast(if (s.freq.v == Frequency.Once) "送信を予約しました" else "定期メッセージを登録しました")
          props.onSaved(()); props.onClose(())
        case scala.util.Failure(e) => Store.error(e)
      }
    }
  }
}

def freqs: List[(String, String)] = Frequency.all.map(f => f -> Frequency.label(f))
def isOnce(s: State) = s.freq.mapC(_ == Frequency.Once)
def isWeekly(s: State) = s.freq.mapC(_ == Frequency.Weekly)
def isMonthly(s: State) = s.freq.mapC(_ == Frequency.Monthly)
</script>

<template>
  <Modal :onClose="props.onClose" :wide="true">
    <h2>⏱ {{ if (props.editing.isDefined) "定期メッセージを変える" else "送信の予約・定期メッセージ" }}</h2>
    <RichEditor :content="content(props)" :channelId="Some(props.channelId)" :onReady="h => s.editor := Some(h)" />
    <div class="grid">
      <label class="field">くり返し
        <select v-model="s.freq"><option v-for="f in freqs" :value="f._1">{{ f._2 }}</option></select></label>
      <label v-if="isOnce(s)" class="field">日付<input type="date" v-model="s.date"></label>
      <label v-if="isWeekly(s)" class="field">曜日
        <select v-model="s.weekday"><option v-for="w in Frequency.weekdays" :value="w._1.toString">{{ w._2 }}曜日</option></select></label>
      <label v-if="isMonthly(s)" class="field">日（月末を超えるときは月末）<input type="number" min="1" max="31" v-model="s.monthday"></label>
      <label class="field">時刻<input type="time" v-model="s.time"></label>
    </div>
    <label class="check"><input type="checkbox" v-model="s.enabled"> 有効にする</label>
    <div class="actions">
      <button type="button" @click="props.onClose(())">キャンセル</button>
      <button type="button" class="primary" :disabled="s.busy" @click="save(s, props)">保存</button>
    </div>
  </Modal>
</template>

<style scoped>
h2 { margin: 0 0 1rem; font-size: 1.15rem; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: .6rem; margin-top: .8rem; }
</style>
