<script lang="scala">
import stui.web.*
import org.scalajs.dom

// オートコンプリート（セレクトボックスの代わり）. 入力した文字で選択肢を絞り込み、上下キー・Enter で選ぶ.
// bind を渡すとその値を読み書きし、渡さなければ value を初期値にする. 選ぶと onChange に値を渡す.
// 選択肢が後から読み込まれるときは live（computed など）で渡す.
case class Props(options: List[Opt] = Nil,
                 live: Option[CRV[List[Opt]]] = None,
                 shows: Option[CRV[String]] = None,
                 value: String = "",
                 bind: Option[RV[String]] = None,
                 onChange: String => Unit = _ => (),
                 placeholder: String = "選択…",
                 disabled: Boolean = false,
                 small: Boolean = false,
                 clearLabel: String = "",
                 keep: Boolean = true)

case class State(sel: RV[String], open: RV[Boolean] = RV(false), q: RV[String] = RV(""), active: RV[Int] = RV(0),
                 input: ElementRef[dom.HTMLInputElement] = ElementRef())

def initState(props: Props) = State(RV(props.bind.map(_.v).getOrElse(props.value)))

def current(s: State, props: Props): RV[String] = props.bind.getOrElse(s.sel)

/** 今の値（shows を渡したときはそれを表示する. 例: 検索欄の条件から読んだ値）. */
def now(s: State, props: Props): String = props.shows.map(_.v).getOrElse(current(s, props).v)

/** 選択肢（clearLabel があれば先頭に「空」の選択肢）. */
def all(props: Props): List[Opt] = (if (props.clearLabel.nonEmpty) List(Opt("", props.clearLabel)) else Nil) ++ props.live.map(_.v).getOrElse(props.options)

def matches(o: Opt, q: String): Boolean = {
  val t = q.trim.toLowerCase
  t.isEmpty || o.label.toLowerCase.contains(t) || o.hint.toLowerCase.contains(t) || o.value.toLowerCase == t
}

def filtered(s: State, props: Props) = computed { val q = s.q.v; all(props).filter(matches(_, q)) }

def selected(s: State, props: Props) = computed { val v = now(s, props); all(props).find(_.value == v) }

/** 入力欄の表示: 開いている間は入力中の文字、閉じているときは選んでいるものの名前. */
def shown(s: State, props: Props) = computed {
  if (s.open.v) s.q.v else selected(s, props).v.map(o => o.label).getOrElse("")
}

def openList(s: State, props: Props): Unit = if (!props.disabled && !s.open.v) {
  s.q := ""
  s.active := math.max(0, all(props).indexWhere(_.value == now(s, props)))
  s.open := true
  s.input.foreach(_.select())
}

def choose(s: State, props: Props, o: Opt): Unit = {
  s.open := false
  if (props.keep) current(s, props) := o.value
  props.onChange(o.value)
  s.input.foreach(_.blur())
}

def key(s: State, props: Props, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  val list = filtered(s, props).v
  k.key match {
    case "ArrowDown" => k.preventDefault(); if (!s.open.v) openList(s, props) else s.active := math.min(list.size - 1, s.active.v + 1)
    case "ArrowUp"   => k.preventDefault(); s.active := math.max(0, s.active.v - 1)
    case "Enter"     => k.preventDefault(); if (s.open.v) list.lift(s.active.v).foreach(choose(s, props, _)) else openList(s, props)
    case "Escape"    => if (s.open.v) { k.preventDefault(); k.stopPropagation(); s.open := false }
    case "Tab"       => s.open := false
    case _           => ()
  }
}

def typed(s: State, props: Props, v: String): Unit = { s.q := v; s.active := 0; if (!s.open.v) s.open := true }

/** 外をクリックしたとき（選択肢のクリックが先に届くよう少し待つ）. */
def blur(s: State): Unit = dom.window.setTimeout(() => s.open := false, 150)

def itemClass(s: State, i: Int) = s.active.mapC(a => if (a == i) "opt active" else "opt")
def cls(props: Props): String = "combo" + (if (props.small) " small" else "") + (if (props.disabled) " disabled" else "")
def indexed(s: State, props: Props) = filtered(s, props).mapC(_.zipWithIndex)
def selIcon(s: State, props: Props) = selected(s, props).mapC(_.map(_.icon).getOrElse(""))
def selColor(s: State, props: Props) = selected(s, props).mapC(_.map(o => if (o.color.isEmpty) "display:none" else s"background:${o.color}").getOrElse("display:none"))
def selUser(s: State, props: Props) = selected(s, props).mapC(_.flatMap(_.user).toList)
def swatch(o: Opt): String = s"background:${o.color}"
</script>

<template>
  <div :class="cls(props)">
    <span class="lead">
      <span class="dot" :style="selColor(s, props)"></span>{{ selIcon(s, props) }}<template v-for="u in selUser(s, props)"><Avatar :user="Some(u)" :size="18" /></template>
    </span>
    <input ref="s.input" type="text" :value="shown(s, props)" :placeholder="props.placeholder" :disabled="props.disabled" autocomplete="off"
      @focus="openList(s, props)" @click="openList(s, props)" @input="typed(s, props, Dom.value(event))" @keydown="key(s, props, event)" @blur="blur(s)">
    <span class="caret">▾</span>
    <div v-if="s.open" class="list">
      <div v-for="oi in indexed(s, props)" :class="itemClass(s, oi._2)" @mousedown.prevent="choose(s, props, oi._1)">
        <span v-if="oi._1.color.nonEmpty" class="dot" :style="swatch(oi._1)"></span>
        <span v-if="oi._1.icon.nonEmpty" class="ic">{{ oi._1.icon }}</span>
        <Avatar v-if="oi._1.user.isDefined" :user="oi._1.user" :size="20" />
        <span class="grow">{{ oi._1.label }}</span>
        <span v-if="oi._1.hint.nonEmpty" class="muted small">{{ oi._1.hint }}</span>
      </div>
      <div v-if="filtered(s, props).mapC(_.isEmpty)" class="none muted small">該当するものがありません</div>
    </div>
  </div>
</template>

<style scoped>
.combo { position: relative; display: flex; align-items: center; border: 1px solid var(--line); border-radius: 7px; background: var(--bg);
  min-width: 6rem; max-width: 100%; }
.combo:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-weak); }
.combo.disabled { opacity: .6; }
.combo input { border: none; background: transparent; box-shadow: none !important; flex: 1; min-width: 0; padding: .35rem .2rem .35rem .3rem; cursor: pointer; }
.combo input:focus { cursor: text; }
.combo.small input { padding: .2rem .2rem .2rem .25rem; font-size: .82rem; }
.lead { display: inline-flex; align-items: center; gap: .25rem; padding-left: .45rem; flex: none; }
.lead:empty { padding-left: .2rem; }
.caret { color: var(--muted); font-size: .7rem; padding: 0 .45rem; pointer-events: none; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; flex: none; }
.list { position: absolute; top: calc(100% + 4px); left: 0; min-width: 100%; max-width: 360px; max-height: 280px; overflow-y: auto; z-index: 1500;
  background: var(--surface); border: 1px solid var(--line); border-radius: 9px; padding: 4px;
  box-shadow: 0 8px 10px -5px rgba(0, 0, 0, .2), 0 16px 24px 2px rgba(0, 0, 0, .14), 0 6px 30px 5px rgba(0, 0, 0, .12); }
.opt { display: flex; align-items: center; gap: .45rem; padding: .38rem .55rem; border-radius: 6px; cursor: pointer; font-size: .88rem; white-space: nowrap; }
.opt.active { background: var(--accent-weak); color: var(--accent); }
.ic { width: 1.2em; text-align: center; }
.none { padding: .45rem .6rem; }
</style>
