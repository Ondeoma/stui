<script lang="scala">
import stui.web.*
import org.scalajs.dom
import stui.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ユーザーを名前で探して選ぶ（入力すると候補を出す. 上下キー・Enter で選ぶ）
case class Props(onPick: UserBrief => Unit, placeholder: String = "名前・メールで探す", autofocus: Boolean = false)

case class State(q: RV[String] = RV(""), list: RV[List[UserBrief]] = RV(Nil), active: RV[Int] = RV(0), timer: RV[Option[Int]] = RV(None))

def initState() = State()

def typed(s: State): Unit = {
  s.timer.v.foreach(dom.window.clearTimeout)
  s.timer := Some(dom.window.setTimeout(() => {
    val q = s.q.v.trim
    if (q.isEmpty) s.list := Nil else Api.users(q).foreach { us => s.list := us.filterNot(_.bot); s.active := 0 }
  }, 150))
}

def pick(s: State, props: Props, u: UserBrief): Unit = { props.onPick(u); s.q := ""; s.list := Nil }

def key(s: State, props: Props, e: dom.KeyboardEvent): Unit = e.key match {
  case "ArrowDown" => e.preventDefault(); s.active := math.min(s.active.v + 1, s.list.v.size - 1)
  case "ArrowUp"   => e.preventDefault(); s.active := math.max(s.active.v - 1, 0)
  case "Enter" if s.list.v.nonEmpty => e.preventDefault(); s.list.v.lift(s.active.v).foreach(pick(s, props, _))
  case _ => ()
}

def items(s: State) = computed { val a = s.active.v; s.list.v.zipWithIndex.map((u, i) => (u, i == a)) }
</script>

<template>
  <div class="up">
    <input type="search" v-model="s.q" :placeholder="props.placeholder" :autofocus="props.autofocus" @input="typed(s)"
      @keydown="key(s, props, event.asInstanceOf[org.scalajs.dom.KeyboardEvent])">
    <div v-if="s.list.mapC(_.nonEmpty)" class="pop">
      <button v-for="x in items(s)" :key="x._1.id" type="button" :class='if (x._2) "opt on" else "opt"' @mousedown.prevent="pick(s, props, x._1)">
        <Avatar :user="Some(x._1)" :size="22" /> {{ x._1.name }}</button>
    </div>
  </div>
</template>

<style scoped>
.up { position: relative; }
.up input { width: 100%; }
.pop { position: absolute; left: 0; right: 0; top: 100%; margin-top: 2px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px;
  box-shadow: var(--shadow); z-index: 40; max-height: 260px; overflow-y: auto; padding: .2rem; }
.opt { display: flex; gap: .5rem; align-items: center; width: 100%; border: none; background: none; justify-content: flex-start; padding: .35rem .5rem;
  border-radius: 6px; }
.opt:hover, .opt.on { background: var(--hover); }
</style>
