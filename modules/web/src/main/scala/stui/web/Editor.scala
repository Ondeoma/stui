package stui.web

import io.circe.Json
import io.circe.parser.parse
import org.scalajs.dom
import stui.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSGlobal

/** エディタ（frontend/src/editor.js の Tiptap. ライブラリを呼ぶ所だけ）. */
@js.native
@JSGlobal("stuiEditor")
private object EditorFacade extends js.Object {
  def create(area: dom.Element, opts: js.Object): EditorJs = js.native
}

/** editor.js の createEditor の戻り値. steps は [[コマンド名, ...引数], ...]（Tiptap の chain のコマンド）. */
@js.native
private trait EditorJs extends js.Object {
  def getJSON(): js.Any = js.native
  def setJSON(j: js.Any): Unit = js.native
  def isEmpty(): Boolean = js.native
  def clear(): Unit = js.native
  def focus(): Unit = js.native
  def insertText(t: String): Unit = js.native
  def destroy(): Unit = js.native
  def run(steps: js.Array[js.Array[js.Any]]): Unit = js.native
  def block(range: js.Any, steps: js.Array[js.Array[js.Any]]): Unit = js.native
  def editLink(): Unit = js.native
  def uploadFiles(files: js.Array[dom.File], pos: js.Any): Unit = js.native
  def isActive(tpe: String, attrs: js.Any): Boolean = js.native
  def attrs(tpe: String): js.Dynamic = js.native
  def isEditable(): Boolean = js.native
  def width(): Double = js.native
  def onUpdate(f: js.Function0[Unit]): Unit = js.native
}

/** 画面から使うエディタの操作. */
final class EditorHandle private[web] (ed: EditorJs, el: dom.Element, fileInput: dom.HTMLInputElement) {
  def getJSON(): js.Any = ed.getJSON()
  def setJSON(j: js.Any): Unit = ed.setJSON(j)
  def isEmpty(): Boolean = ed.isEmpty()
  def clear(): Unit = ed.clear()
  def focus(): Unit = ed.focus()
  def insertText(t: String): Unit = ed.insertText(t)
  def pickFile(): Unit = fileInput.click()
  def destroy(): Unit = { ed.destroy(); el.innerHTML = ""; el.classList.remove("sk-editor") }
}

object Editor {

  def toJs(j: Json): js.Any = js.JSON.parse(j.noSpaces)

  def fromJs(a: js.Any): Json = parse(js.JSON.stringify(a)).getOrElse(Json.Null)

  /** セルの背景色（色名を data-bg に保存し、実際の色は CSS で決める. ダークモード対応）. */
  val cellColors: List[(String, String)] = List(
    "gray" -> "グレー", "brown" -> "ブラウン", "orange" -> "オレンジ", "yellow" -> "イエロー", "green" -> "グリーン",
    "blue" -> "ブルー", "purple" -> "パープル", "pink" -> "ピンク", "red" -> "レッド")

  private type Steps = js.Array[js.Array[js.Any]]

  /** Tiptap の chain のコマンド（名前と引数）を続けて行う手順. */
  private def steps(cmds: (String, Seq[js.Any])*): Steps = cmds.map((n, a) => js.Array[js.Any]((n: js.Any) +: a*)).toJSArray
  private def cmd(name: String, args: js.Any*): Steps = steps(name -> args)

  private def el[A <: dom.HTMLElement](tag: String, cls: String = ""): A = {
    val e = dom.document.createElement(tag).asInstanceOf[A]
    if (cls.nonEmpty) e.className = cls
    e
  }

  /** ツールバーのボタン（押してもエディタのフォーカスを外さない）. */
  private def button(cls: String, html: String, title: String)(f: => Unit): dom.HTMLButtonElement = {
    val b = el[dom.HTMLButtonElement]("button", cls)
    b.`type` = "button"
    b.innerHTML = html
    b.title = title
    b.addEventListener("mousedown", (e: dom.Event) => e.preventDefault())
    b.addEventListener("click", (_: dom.Event) => f)
    b
  }

  // scalajs-dom の HTMLElement に hidden が無いため
  private def setHidden(e: dom.HTMLElement, v: Boolean): Unit = e.asInstanceOf[js.Dynamic].hidden = v

  private def sep(): dom.HTMLElement = el("span", "sk-sep")

  private val table33 = js.Dynamic.literal(rows = 3, cols = 3, withHeaderRow = true)
  private def level(n: Int) = js.Dynamic.literal(level = n)

  /** ツールバーのボタン（表示, 説明, 押したとき, 押されている状態の判定（型と属性））. None は区切り. */
  private final case class Tool(label: String, title: String, run: EditorJs => Unit, active: Option[(String, js.Any)] = None)

  private def toggle(label: String, title: String, command: String, tpe: String, args: js.Any*): Tool =
    Tool(label, title, _.run(cmd(command, args*)), Some(tpe -> args.headOption.getOrElse(js.undefined)))

  private val tools: List[Option[Tool]] = List(
    Some(toggle("<b>B</b>", "太字 (Ctrl+B)", "toggleBold", "bold")),
    Some(toggle("<i>I</i>", "斜体 (Ctrl+I)", "toggleItalic", "italic")),
    Some(toggle("<s>S</s>", "取り消し線", "toggleStrike", "strike")),
    Some(toggle("&lt;/&gt;", "コード", "toggleCode", "code")),
    None,
    Some(toggle("H2", "見出し", "toggleHeading", "heading", level(2))),
    Some(toggle("H3", "小見出し", "toggleHeading", "heading", level(3))),
    Some(toggle("•", "箇条書き", "toggleBulletList", "bulletList")),
    Some(toggle("1.", "番号付きリスト", "toggleOrderedList", "orderedList")),
    Some(toggle("☑", "ToDo", "toggleTaskList", "taskList")),
    Some(toggle("❝", "引用", "toggleBlockquote", "blockquote")),
    Some(toggle("{ }", "コードブロック", "toggleCodeBlock", "codeBlock")),
    None,
    Some(Tool("🔗", "リンク", _.editLink(), Some("link" -> js.undefined))),
    Some(Tool("▦", "表", _.run(cmd("insertTable", table33)))),
  )

  /** 「/」で開くブロックの挿入メニュー. ed は作り終えたエディタ（選んだときに使う）. */
  private def slashItems(ed: () => EditorJs, upload: Boolean, mentions: Boolean, pickFile: () => Unit): List[js.Dynamic] = {
    def item(icon: String, label: String, hint: String, keys: String, iconClass: String = "")(run: js.Any => Unit): js.Dynamic =
      js.Dynamic.literal(icon = icon, iconClass = iconClass, label = label, hint = hint, keys = keys, run = (run: js.Function1[js.Any, Unit]))
    // 文字のある行で選んだときは、その行を変えずに次の行に入れる（editor.js の block）
    def block(icon: String, label: String, hint: String, keys: String)(command: String, args: js.Any*): js.Dynamic =
      item(icon, label, hint, keys)(range => ed().block(range, cmd(command, args*)))
    def deleteRange(range: js.Any): Unit = ed().run(cmd("deleteRange", range))
    List(
      block("H2", "見出し", "大きな見出し", "heading h2 midashi みだし")("setNode", "heading", level(2)),
      block("H3", "小見出し", "小さな見出し", "heading h3 komidashi こみだし")("setNode", "heading", level(3)),
      block("¶", "本文", "普通の段落", "paragraph text honbun ほんぶん")("setParagraph"),
      block("•", "箇条書き", "", "bullet list ul kajougaki かじょうがき")("toggleBulletList"),
      block("1.", "番号付きリスト", "", "ordered number list ol bangou ばんごう")("toggleOrderedList"),
      block("☑", "ToDo", "チェックボックス付きのリスト", "todo task check")("toggleTaskList"),
      block("❝", "引用", "", "quote blockquote inyou いんよう")("toggleBlockquote"),
      block("{ }", "コードブロック", "言語を選んでハイライト", "code block kodo こーど")("toggleCodeBlock"),
      block("▦", "表", "3×3 の表", "table hyou ひょう")("insertTable", table33),
      block("―", "区切り線", "", "hr horizontal rule divider kugiri くぎり")("setHorizontalRule"),
      item("🔗", "リンク", "", "link url rinku りんく") { range => deleteRange(range); ed().editLink() },
    ) ++ Option.when(upload)(item("", "画像・ファイル", "添付して埋め込む", "image file gazou がぞう upload", "ico ico-image") { range =>
      deleteRange(range); pickFile()
    }) ++ Option.when(mentions)(item("@", "メンション", "ユーザーに通知する", "mention user") { range =>
      ed().run(steps("deleteRange" -> Seq(range), "insertContent" -> Seq("@")))
    })
  }

  /** 選んでいるものに応じたツールバー（画像の寄せ方向・大きさ、表のセルの色・行と列）. */
  private def contextBar(ed: EditorJs, bar: dom.HTMLElement): () => Unit = {
    def btn(label: String, title: String, active: Boolean = false)(f: => Unit) = button("sk-tb" + (if (active) " active" else ""), label, title)(f)
    def label(t: String) = { val s = el[dom.HTMLElement]("span", "sk-ctx-label"); s.textContent = t; s }
    def run(command: String, args: js.Any*): Unit = ed.run(cmd(command, args*))
    def str(v: js.Dynamic): Option[String] = Option(v).filterNot(js.isUndefined).map(_.toString)
    () => {
      bar.innerHTML = ""
      if (!ed.isEditable()) setHidden(bar, true)
      else if (ed.isActive("image", js.undefined)) {
        val align = str(ed.attrs("image").align)
        def setAlign(v: js.Any): Unit = run("updateAttributes", "image", js.Dynamic.literal(align = v))
        def width(ratio: Option[Double]): Unit = {
          val area = Some(ed.width()).filter(_ > 0).getOrElse(600d)
          run("updateAttributes", "image", js.Dynamic.literal(width = ratio.fold(null: js.Any)(r => math.round((area - 24) * r).toDouble), height = null))
        }
        List(label("画像"),
          btn("⇤", "左寄せ", align.forall(_ == "left"))(setAlign(null)),
          btn("↔", "中央", align.contains("center"))(setAlign("center")),
          btn("⇥", "右寄せ", align.contains("right"))(setAlign("right")),
          sep(),
          btn("小", "幅 25%")(width(Some(0.25))), btn("中", "幅 50%")(width(Some(0.5))), btn("大", "幅 75%")(width(Some(0.75))),
          btn("全幅", "幅 100%")(width(Some(1.0))), btn("元", "元の大きさ")(width(None)),
          sep(),
          btn("🗑", "画像を削除")(run("deleteSelection"))).foreach(bar.appendChild)
        setHidden(bar, false)
      } else if (ed.isActive("table", js.undefined)) {
        val cur = str(ed.attrs("tableCell").backgroundColor).orElse(str(ed.attrs("tableHeader").backgroundColor))
        def color(id: js.Any): Unit = run("setCellAttribute", "backgroundColor", id)
        (List(label("セルの色"), btn("<span class=\"sk-swatch none\"></span>", "なし", cur.isEmpty)(color(null))) ++
          cellColors.map((id, name) => btn(s"""<span class="sk-swatch" data-bg="$id"></span>""", name, cur.contains(id))(color(id))) ++
          List(sep(), label("行"),
            btn("＋↑", "上に行を追加")(run("addRowBefore")),
            btn("＋↓", "下に行を追加")(run("addRowAfter")),
            btn("－", "行を削除")(run("deleteRow")),
            sep(), label("列"),
            btn("＋←", "左に列を追加")(run("addColumnBefore")),
            btn("＋→", "右に列を追加")(run("addColumnAfter")),
            btn("－", "列を削除")(run("deleteColumn")),
            sep(),
            btn("見出し行", "先頭の行を見出しにする／戻す")(run("toggleHeaderRow")),
            btn("🗑", "表を削除")(run("deleteTable")))).foreach(bar.appendChild)
        setHidden(bar, false)
      } else setHidden(bar, true)
    }
  }

  /**
   * エディタを作る. channelId を渡すと @メンションの候補をそのチャンネルのメンバーから出す.
   * upload を渡すと、画像・ファイルの貼り付けでそれを呼ぶ（Some を返すと本文に埋め込む. None なら埋め込まない）.
   */
  def create(el: dom.Element, content: Option[Json], placeholder: String, channelId: Option[UUID], onSubmit: () => Unit,
             autofocus: Boolean = false, onChange: Json => Unit = _ => (), enterSubmit: Boolean = false,
             upload: Option[dom.File => Future[Option[AttachmentDto]]] = None, onArrowUp: Option[() => Unit] = None,
             toolbar: Boolean = true): EditorHandle = {
    el.classList.add("sk-editor")
    val toolbarEl = this.el[dom.HTMLElement]("div", "sk-toolbar")
    val ctxBar = this.el[dom.HTMLElement]("div", "sk-toolbar sk-ctx")
    setHidden(ctxBar, true)
    val area = this.el[dom.HTMLElement]("div", "sk-area")
    if (toolbar) el.appendChild(toolbarEl)
    el.appendChild(ctxBar)
    el.appendChild(area)
    val fileInput = this.el[dom.HTMLInputElement]("input")
    fileInput.`type` = "file"
    fileInput.multiple = true
    fileInput.style.display = "none"

    // 候補の一覧・メニューの項目は、エディタを作り終えてから選ばれる
    var ed: EditorJs = null
    // メンションの候補: 人と、まとめて届くグループ（「グループ・3人」のように出す）
    val mentions: js.Function1[String, js.Promise[js.Array[js.Object]]] = (q: String) => {
      val ql = q.toLowerCase
      val people = channelId.fold(Api.users(q))(Store.members)
      val groups = channelId.fold(Future.successful(List.empty[MentionGroupDto]))(c => Store.mentionGroups(c).recover(_ => Nil))
      people.zip(groups).map { (us, gs) =>
        (us.filter(u => ql.isEmpty || u.name.toLowerCase.contains(ql)).map(u => js.Dynamic.literal(id = u.id.toString, label = u.name)) ++
          gs.filter(g => ql.isEmpty || g.name.toLowerCase.contains(ql)).map(g => js.Dynamic.literal(id = g.id, label = g.name, icon = "👥",
            hint = s"${if (g.id.startsWith("r:")) "チャンネルの全員" else "グループ"}・${g.count}人")))
          .take(12).map(_.asInstanceOf[js.Object]).toJSArray
      }.toJSPromise
    }
    val channels: js.Function1[String, js.Promise[js.Array[js.Object]]] = (q: String) => {
      val ql = q.toLowerCase
      Future.successful(Store.channels.v.filterNot(c => ChannelKind.isDm(c.kind)).filter(c => ql.isEmpty || c.name.toLowerCase.contains(ql))
        .take(12).map(c => js.Dynamic.literal(id = c.id.toString, label = c.name, icon = if (c.kind == ChannelKind.Private) "🔒" else "#").asInstanceOf[js.Object])
        .toJSArray).toJSPromise
    }
    val slash = slashItems(() => ed, upload.isDefined, mentions = true, () => fileInput.click())
    val slashFn: js.Function1[String, js.Array[js.Dynamic]] = (query: String) => {
      val q = query.toLowerCase
      slash.filter(i => q.isEmpty || i.label.toString.toLowerCase.contains(q) || i.keys.toString.contains(q)).take(12).toJSArray
    }
    val up: js.Any = upload match {
      case None => null
      case Some(f) =>
        val g: js.Function1[dom.File, js.Promise[js.Any]] = (file: dom.File) =>
          f(file).map {
            case Some(a) => js.Dynamic.literal(url = a.url, name = a.fileName, image = a.contentType.startsWith("image/")).asInstanceOf[js.Any]
            case None    => null.asInstanceOf[js.Any]
          }.toJSPromise
        g
    }
    val o = js.Dynamic.literal(
      content = content.map(toJs).getOrElse(null),
      placeholder = placeholder,
      languages = Languages.sorted,
      cellColors = cellColors.map(_._1).toJSArray,
      mentions = mentions,
      channels = channels,
      slashItems = slashFn,
      popup = ((empty: String) => Popup.render(empty)): js.Function1[String, js.Function0[js.Object]],
      popupOpen = (() => Popup.isOpen): js.Function0[Boolean],
      upload = up,
      prompt = ((m: String, d: String) => Dialog.promptJs(m, d)): js.Function2[String, String, js.Promise[js.Any]],
      alert = ((m: String) => Dialog.alert(m)): js.Function1[String, Unit],
      autofocus = autofocus,
      enterSubmit = enterSubmit,
      onSubmit = (() => onSubmit()): js.Function0[Unit],
      onChange = ((j: js.Any) => onChange(fromJs(j))): js.Function1[js.Any, Unit],
      onArrowUp = onArrowUp.fold(null: js.Any)(f => (() => f()): js.Function0[Unit])
    )
    ed = EditorFacade.create(area, o.asInstanceOf[js.Object])

    // ツールバー
    fileInput.addEventListener("change", (_: dom.Event) => {
      ed.uploadFiles(Option(fileInput.files).fold(js.Array[dom.File]())(fs => (0 until fs.length).map(fs(_)).toJSArray), null)
      fileInput.value = ""
    })
    toolbarEl.appendChild(fileInput)
    val tb = tools ++ Option.when(upload.isDefined)(Some(Tool("""<span class="ico ico-image"></span>""", "画像・ファイルを添付", _ => fileInput.click())))
    val buttons = tb.flatMap {
      case None => toolbarEl.appendChild(sep()); None
      case Some(t) =>
        val b = button("sk-tb", t.label, t.title)(t.run(ed))
        toolbarEl.appendChild(b)
        Some(b -> t.active)
    }
    val hint = this.el[dom.HTMLElement]("span", "sk-tb-hint")
    hint.textContent = "「/」でメニュー"
    toolbarEl.appendChild(hint)
    val renderCtx = contextBar(ed, ctxBar)
    def refresh(): Unit = buttons.foreach((b, active) => b.classList.toggle("active", active.exists((t, a) => ed.isActive(t, a))))
    ed.onUpdate(() => { refresh(); renderCtx() })
    renderCtx()
    new EditorHandle(ed, el, fileInput)
  }

}

/** 入力中の文字（@ や # や /）の下に出す候補の一覧（Tiptap の Suggestion の render）. 項目は {label, icon?, iconClass?, hint?}. */
private object Popup {

  /** 開いている一覧の数（Enter で送るとき、候補を選ぶ Enter と区別する）. */
  private var open = 0

  def isOpen: Boolean = open > 0

  private def text(v: js.Dynamic): Option[String] = Option(v).filterNot(js.isUndefined).map(_.toString).filter(_.nonEmpty)

  def render(emptyText: String): js.Function0[js.Object] = () => {
    var box: Option[dom.HTMLElement] = None
    var items = js.Array[js.Dynamic]()
    var selected = 0
    var command: js.Dynamic = null
    def span(cls: String, t: String = ""): dom.HTMLElement = {
      val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
      if (cls.nonEmpty) s.className = cls
      if (t.nonEmpty) s.textContent = t
      s
    }
    def draw(): Unit = box.foreach { bx =>
      bx.innerHTML = ""
      if (items.isEmpty) {
        val e = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
        e.className = "sk-mention-empty"
        e.textContent = emptyText
        bx.appendChild(e)
      } else items.zipWithIndex.foreach { (it, i) =>
        val b = dom.document.createElement("button").asInstanceOf[dom.HTMLButtonElement]
        b.`type` = "button"
        b.className = "sk-mention-item" + (if (i == selected) " selected" else "")
        (text(it.iconClass), text(it.icon)) match {
          case (Some(c), _) => val ic = span("sk-pop-icon"); ic.appendChild(span(c)); b.appendChild(ic)
          case (_, Some(t)) => b.appendChild(span("sk-pop-icon", t))
          case _            => ()
        }
        val l = span(""); l.textContent = it.label.toString; b.appendChild(l)
        text(it.hint).foreach(h => b.appendChild(span("sk-pop-hint", h)))
        b.addEventListener("mousedown", (ev: dom.Event) => { ev.preventDefault(); if (command != null) command(it) })
        bx.appendChild(b)
        if (i == selected) b.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest"))
      }
    }
    def place(rect: js.Dynamic): Unit = box.foreach { bx =>
      if (rect != null && !js.isUndefined(rect)) {
        val r = rect()
        if (r != null && !js.isUndefined(r)) {
          val w = dom.window
          val (left, top, bottom) = (r.left.asInstanceOf[Double], r.top.asInstanceOf[Double], r.bottom.asInstanceOf[Double])
          bx.style.left = s"${math.min(left + w.scrollX, w.scrollX + w.innerWidth - 260)}px"
          // 画面の下のほう（チャットの入力欄）では上に出す
          val below = w.innerHeight - bottom
          if (below < 260 && top > below) {
            bx.style.top = ""
            bx.style.bottom = s"${w.innerHeight - top - w.scrollY + 4}px"
          } else {
            bx.style.bottom = ""
            bx.style.top = s"${bottom + w.scrollY + 4}px"
          }
        }
      }
    }
    def take(props: js.Dynamic): Unit = {
      items = props.items.asInstanceOf[js.Array[js.Dynamic]]; selected = 0; command = props.command
      draw(); place(props.clientRect)
    }
    js.Dynamic.literal(
      onStart = { (props: js.Dynamic) =>
        open += 1
        val bx = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
        bx.className = "sk-mention-box"
        dom.document.body.appendChild(bx)
        box = Some(bx)
        take(props)
      }: js.Function1[js.Dynamic, Unit],
      onUpdate = ((props: js.Dynamic) => take(props)): js.Function1[js.Dynamic, Unit],
      onKeyDown = { (p: js.Dynamic) =>
        val n = math.max(items.length, 1)
        p.event.key.toString match {
          case "ArrowDown"                                   => selected = (selected + 1) % n; draw(); true
          case "ArrowUp"                                     => selected = (selected + items.length - 1) % n; draw(); true
          case "Enter" | "Tab" if selected < items.length    => command(items(selected)); true
          case "Escape"                                      => box.foreach(_.remove()); box = None; true
          case _                                             => false
        }
      }: js.Function1[js.Dynamic, Boolean],
      onExit = { (_: js.Dynamic) =>
        open = math.max(0, open - 1)
        box.foreach(_.remove()); box = None
      }: js.Function1[js.Dynamic, Unit]
    ).asInstanceOf[js.Object]
  }

}
