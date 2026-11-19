package stui.core

import io.circe.{ACursor, Json}
import stui.shared.Html

import java.util.UUID

/**
 * Tiptap(ProseMirror) の JSON 文書の変換。
 * 画面から受け取った JSON は信頼せず、許可したノード・マーク・属性だけから HTML を組み立てる（XSS 対策）。
 */
object RichText {

  /** 空の文書。 */
  val empty: Json = Json.obj("type" -> Json.fromString("doc"), "content" -> Json.arr())

  /** 平文から文書を作る（API・自動化・メールの本文など）。空行で段落を分け、行の区切りは改行にする。 */
  def fromPlain(
    text: String
  ): Json = {
    val paras = text.replace("\r\n", "\n").split("\n\n").toList.filter(_.trim.nonEmpty)
    Json.obj(
      "type" -> Json.fromString("doc"),
      "content" -> Json.fromValues(paras.map { p =>
        val lines = p.split("\n").toList
        val inl = lines.zipWithIndex.flatMap {
          (
            l,
            i
          ) =>
            val t =
              if l.isEmpty then Nil else List(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(l)))
            if i < lines.size - 1 then t :+ Json.obj("type" -> Json.fromString("hardBreak")) else t
        }
        Json.obj("type" -> Json.fromString("paragraph"), "content" -> Json.fromValues(inl))
      })
    )
  }

  /** Markdown 風の簡単な入力（API・MCP 向け）。見出し(#)・箇条書き(-, *)・番号付き(1.)・引用(>)・コード(```)だけ解釈する。 */
  /** Markdown として読む長さの上限（Webhook・連携サービスから大きすぎる本文が来ても、処理に時間をかけない）。 */
  val MaxMarkdown = 100000

  def fromMarkdown(
    md: String
  ): Json = {
    val lines = md.take(MaxMarkdown).replace("\r\n", "\n").split("\n", -1).toList
    val blocks = List.newBuilder[Json]
    def text(
      s: String
    ): List[Json] = inlines(s)
    def para(
      s: String
    ) = Json.obj("type" -> Json.fromString("paragraph"), "content" -> Json.fromValues(text(s)))
    def item(
      s: String
    ) = Json.obj("type" -> Json.fromString("listItem"), "content" -> Json.arr(para(s)))
    var rest = lines
    while rest.nonEmpty do {
      val l = rest.head
      if l.trim.startsWith("```") then {
        val lang = l.trim.drop(3).trim
        val (code, after) = rest.tail.span(x => !x.trim.startsWith("```"))
        blocks += Json.obj(
          "type" -> Json.fromString("codeBlock"),
          "attrs" -> Json.obj("language" -> (if lang.isEmpty then Json.Null else Json.fromString(lang))),
          "content" ->
            (if code.isEmpty then Json.arr()
             else Json.arr(Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(code.mkString("\n")))))
        )
        rest = after.drop(1)
      } else if l.matches("^#{1,6} .*") then {
        val level = l.takeWhile(_ == '#').length
        blocks += Json.obj(
          "type" -> Json.fromString("heading"),
          "attrs" -> Json.obj("level" -> Json.fromInt(level)),
          "content" -> Json.fromValues(text(l.drop(level).trim))
        )
        rest = rest.tail
      } else if l.matches("^\\s*[-*] \\[[ xX]\\] .*") then {
        val (items, after) = rest.span(_.matches("^\\s*[-*] \\[[ xX]\\] .*"))
        blocks += Json.obj(
          "type" -> Json.fromString("taskList"),
          "content" -> Json.fromValues(items.map { x =>
            val t = x.trim.drop(2)
            Json.obj(
              "type" -> Json.fromString("taskItem"),
              "attrs" -> Json.obj("checked" -> Json.fromBoolean(t(1) != ' ')),
              "content" -> Json.arr(para(t.drop(3).trim))
            )
          })
        )
        rest = after
      } else if l.matches("^\\s*[-*] .*") then {
        val (items, after) = rest.span(_.matches("^\\s*[-*] .*"))
        blocks += Json.obj(
          "type" -> Json.fromString("bulletList"),
          "content" -> Json.fromValues(items.map(x => item(x.trim.drop(2))))
        )
        rest = after
      } else if l.matches("^\\s*\\d+\\. .*") then {
        val (items, after) = rest.span(_.matches("^\\s*\\d+\\. .*"))
        blocks += Json.obj(
          "type" -> Json.fromString("orderedList"),
          "content" -> Json.fromValues(items.map(x => item(x.trim.dropWhile(_ != ' ').trim)))
        )
        rest = after
      } else if l.startsWith(">") then {
        val (qs, after) = rest.span(_.startsWith(">"))
        blocks += Json.obj(
          "type" -> Json.fromString("blockquote"),
          "content" -> Json.arr(para(qs.map(_.drop(1).trim).mkString(" ")))
        )
        rest = after
      } else if l.trim.isEmpty then rest = rest.tail
      else {
        val (ps, after) = rest.span(x =>
          x.trim.nonEmpty && !x.startsWith("#") && !x.trim.startsWith("```") && !x.matches("^\\s*([-*]|\\d+\\.) .*") &&
            !x.startsWith(">")
        )
        blocks += Json.obj(
          "type" -> Json.fromString("paragraph"),
          "content" -> Json.fromValues(
            ps.zipWithIndex.flatMap(
              (
                x,
                i
              ) => text(x) ++ (if i < ps.size - 1 then List(Json.obj("type" -> Json.fromString("hardBreak"))) else Nil)
            )
          )
        )
        rest = after
      }
    }
    Json.obj("type" -> Json.fromString("doc"), "content" -> Json.fromValues(blocks.result()))
  }

  /**
   * **太字**・*斜体*・~~取り消し~~・`コード`・[文字](URL)・<URL|文字>（Slack の書き方）・URL だけ解釈する。
   * 記号の前の `\`（CommonMark のバックスラッシュエスケープ）は、記号を記法ではなく文字として扱う。
   */
  private def inlines(
    s: String
  ): List[Json] = inlineRuns(hideEscapes(s))

  /** エスケープした記号を置き換える文字の範囲（私用領域。記法の正規表現に当たらないようにする）。 */
  private val EscBase = 0xe000

  private def isAsciiPunct(
    c: Char
  ): Boolean = c >= '!' && c <= '~' && !c.isLetterOrDigit

  private def isHidden(
    c: Char
  ): Boolean = c >= (EscBase + '!') && c <= (EscBase + '~')

  /** `\記号` を私用領域の1文字に置き換える。コード(`…`)の中はそのまま残す（CommonMark と同じ）。 */
  private def hideEscapes(
    s: String
  ): String = {
    val b = new StringBuilder
    var i = 0
    while i < s.length do {
      val c = s(i)
      if c == '\\' && i + 1 < s.length && isAsciiPunct(s(i + 1)) then {
        b.append((EscBase + s(i + 1)).toChar); i += 2
      } else if c == '`' then {
        val run = s.indexWhere(_ != '`', i) match { case -1 => s.length - i; case j => j - i }
        val delim = "`" * run
        // 同じ長さの ` の並びで閉じるところまで（閉じなければ ` はただの文字）。
        // 探す範囲はコードの正規表現と同じく 2000 文字までにし、閉じない ` が続いても処理時間が2乗に伸びないようにする
        val w = s.substring(i, math.min(s.length, i + run + 2001 + run))
        var k = w.indexOf(delim, run)
        while k >= 0 && k + run < w.length && w(k + run) == '`' do
          k = w.indexOf(delim, w.indexWhere(_ != '`', k) match { case -1 => w.length; case j => j })
        if k < 0 then { b.append(delim); i += run }
        else { b.append(w.substring(0, k + run)); i += k + run }
      } else { b.append(c); i += 1 }
    }
    b.toString
  }

  /** 置き換えた文字を戻す。inCode ならコードの中なので `\` も残す。 */
  private def showEscapes(
    s: String,
    inCode: Boolean = false
  ): String =
    if !s.exists(isHidden) then s
    else s.flatMap(c => if isHidden(c) then (if inCode then "\\" else "") + (c - EscBase).toChar else c.toString)

  private def inlineRuns(
    s: String
  ): List[Json] = {
    // 繰り返しに上限を付け、閉じない記号が続いても処理時間が入力の2乗に伸びないようにする
    val re =
      """(\*\*[^*]{1,2000}\*\*|~~[^~]{1,2000}~~|`[^`]{1,2000}`|\[[^\[\]]{1,500}\]\([^)\s]{1,2000}\)|<https?://[^<>|\s]{1,2000}(?:\|[^<>]{1,500})?>|https?://[^\s<>()]{1,2000}|\*[^*\s][^*]{0,2000}\*)""".r
    val out = List.newBuilder[Json]
    var pos = 0
    def plain(
      t: String,
      marks: List[Json] = Nil
    ) =
      if t.nonEmpty then {
        val inCode = marks.exists(m => m.hcursor.get[String]("type").contains("code"))
        out += Json.obj(("type" -> Json.fromString("text")) :: ("text" -> Json.fromString(showEscapes(t, inCode))) ::
          (if marks.isEmpty then Nil else List("marks" -> Json.fromValues(marks)))*)
      }
    def mark(
      t: String
    ) = Json.obj("type" -> Json.fromString(t))
    // 太字・取り消しの中のリンクなども解釈する（中の文字に印を足す）
    def nested(
      inner: String,
      m: Json
    ): Unit =
      inlineRuns(inner).foreach { n =>
        val ms = n.hcursor.downField("marks").focus.flatMap(_.asArray).map(_.toList).getOrElse(Nil)
        out += n.mapObject(_.add("marks", Json.fromValues(m :: ms)))
      }
    re.findAllMatchIn(s).foreach { m =>
      plain(s.substring(pos, m.start))
      val x = m.matched
      if x.startsWith("**") then nested(x.drop(2).dropRight(2), mark("bold"))
      else if x.startsWith("~~") then nested(x.drop(2).dropRight(2), mark("strike"))
      else if x.startsWith("`") then plain(x.drop(1).dropRight(1), List(mark("code")))
      else if x.startsWith("<") then {
        val inner = x.drop(1).dropRight(1)
        val href = inner.takeWhile(_ != '|')
        val label = if inner.contains('|') then inner.dropWhile(_ != '|').drop(1) else href
        plain(
          label,
          List(Json.obj(
            "type" -> Json.fromString("link"),
            "attrs" -> Json.obj("href" -> Json.fromString(showEscapes(href)))
          ))
        )
      } else if x.startsWith("http") then
        plain(
          x,
          List(Json.obj(
            "type" -> Json.fromString("link"),
            "attrs" -> Json.obj("href" -> Json.fromString(showEscapes(x)))
          ))
        )
      else if x.startsWith("[") then {
        val label = x.drop(1).takeWhile(_ != ']')
        val href = x.dropWhile(_ != '(').drop(1).dropRight(1)
        plain(
          label,
          List(Json.obj(
            "type" -> Json.fromString("link"),
            "attrs" -> Json.obj("href" -> Json.fromString(showEscapes(href)))
          ))
        )
      } else plain(x.drop(1).dropRight(1), List(mark("italic")))
      pos = m.end
    }
    plain(s.substring(pos))
    out.result()
  }

  // ------------------------------------------------------------------ HTML

  /** 許可した要素だけから HTML を組み立てる。 */
  def toHtml(
    doc: Json
  ): String = {
    val b = new StringBuilder
    nodes(doc.hcursor).foreach(n => node(n, b))
    b.toString
  }

  private def nodes(
    c: ACursor
  ): List[Json] = c.downField("content").focus.flatMap(_.asArray).map(_.toList).getOrElse(Nil)
  private def tpe(
    n: Json
  ): String = n.hcursor.get[String]("type").getOrElse("")
  private def attr(
    n: Json,
    k: String
  ): ACursor = n.hcursor.downField("attrs").downField(k)

  /** リンクに使える URL（http(s)・mailto・相対パス）。 */
  def safeUrl(
    u: String
  ): Option[String] = {
    val t = u.trim
    val l = t.toLowerCase
    // "//…" と "/\…" はブラウザが別のサイトとして扱うので許さない。制御文字・空白を含むものも許さない
    if t.exists(c => c.isControl || c == ' ') then None
    else if l.startsWith("http://") || l.startsWith("https://") || l.startsWith("mailto:") ||
      (t.startsWith("/") && !t.startsWith("//") && !t.startsWith("/\\")) || t.startsWith("#")
    then Some(t)
    else None
  }

  private def children(
    n: Json,
    b: StringBuilder
  ): Unit = nodes(n.hcursor).foreach(node(_, b))

  private def wrap(
    tag: String,
    n: Json,
    b: StringBuilder,
    attrs: String = ""
  ): Unit = {
    b.append('<').append(tag).append(attrs).append('>')
    children(n, b)
    b.append("</").append(tag).append('>')
  }

  /** 表のセルの背景色（色名だけを許す。実際の色は CSS で決める）。 */
  val cellColors: Set[String] = Set("gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")

  private def cellAttrs(
    n: Json
  ): String = {
    val cs = attr(n, "colspan").as[Int].toOption.filter(_ > 1).fold("")(v => s""" colspan="$v"""")
    val rs = attr(n, "rowspan").as[Int].toOption.filter(_ > 1).fold("")(v => s""" rowspan="$v"""")
    val bg = attr(n, "backgroundColor").as[String].toOption.filter(cellColors).fold("")(c => s""" data-bg="$c"""")
    cs + rs + bg
  }

  private def node(
    n: Json,
    b: StringBuilder
  ): Unit = tpe(n) match {
    case "doc" => children(n, b)
    case "paragraph" => wrap("p", n, b)
    case "heading" =>
      val lv = attr(n, "level").as[Int].toOption.filter(l => l >= 1 && l <= 6).getOrElse(2)
      wrap(s"h$lv", n, b)
    case "bulletList" => wrap("ul", n, b)
    case "orderedList" =>
      val start = attr(n, "start").as[Int].toOption.filter(_ != 1).fold("")(s => s""" start="$s"""")
      wrap("ol", n, b, start)
    case "listItem" => wrap("li", n, b)
    case "taskList" => wrap("ul", n, b, """ class="task-list"""")
    case "taskItem" =>
      val checked = attr(n, "checked").as[Boolean].getOrElse(false)
      b.append(s"""<li class="task-item${if checked then " checked" else ""}"><input type="checkbox" disabled${
          if checked then " checked" else ""
        }><div>""")
      children(n, b)
      b.append("</div></li>")
    case "blockquote" => wrap("blockquote", n, b)
    case "codeBlock" =>
      val lang = attr(n, "language").as[String].toOption.filter(_.matches("^[A-Za-z0-9_+-]{1,30}$"))
      b.append("<pre><code").append(lang.fold("")(l => s""" class="language-$l"""")).append('>')
      b.append(Html.escape(plainOf(n)))
      b.append("</code></pre>")
    case "horizontalRule" => b.append("<hr>")
    case "hardBreak" => b.append("<br>")
    case "image" =>
      attr(n, "src").as[String].toOption.flatMap(safeUrl).filter(_.startsWith("/")).foreach { src =>
        val alt = attr(n, "alt").as[String].getOrElse("")
        val width = attr(n, "width").as[Double].toOption.map(_.round.toInt).filter(w => w >= 16 && w <= 4000).fold("")(
          w => s""" width="$w""""
        )
        val align = attr(n, "align").as[String].toOption.filter(Set("left", "center", "right")).fold("")(a =>
          s""" data-align="$a""""
        )
        b.append(s"""<img src="${Html.escape(src)}" alt="${Html.escape(alt)}"$width$align loading="lazy">""")
      }
    case "table" => b.append("<table>"); b.append("<tbody>"); children(n, b); b.append("</tbody></table>")
    case "tableRow" => wrap("tr", n, b)
    case "tableHeader" => wrap("th", n, b, cellAttrs(n))
    case "tableCell" => wrap("td", n, b, cellAttrs(n))
    case "mention" =>
      val raw = attr(n, "id").as[String].getOrElse("")
      val id = uuidOpt(raw)
      val group = Option(raw).filter(isGroupKey)
      val label = attr(n, "label").as[String].getOrElse("")
      val data = id.fold(group.fold("")(g => s""" data-group="${Html.escape(g)}""""))(i => s""" data-user-id="$i"""")
      b.append(s"""<span class="${if group.isDefined then "mention group" else "mention"}"$data>@${Html.escape(
          label
        )}</span>""")
    case "channelMention" =>
      // チャンネルへのリンク（#開発）
      val id = attr(n, "id").as[String].toOption.flatMap(uuidOpt)
      val label = attr(n, "label").as[String].getOrElse("")
      id.foreach(i => b.append(s"""<a class="channel-ref" href="/c/$i">#${Html.escape(label)}</a>"""))
    case "text" => text(n, b)
    case _ => children(n, b) // 知らないノードは中身だけ出す
  }

  private def text(
    n: Json,
    b: StringBuilder
  ): Unit = {
    val t = Html.escape(n.hcursor.get[String]("text").getOrElse(""))
    val marks = n.hcursor.downField("marks").focus.flatMap(_.asArray).map(_.toList).getOrElse(Nil)
    val (open, close) = marks.foldLeft((List.empty[String], List.empty[String])) { case ((o, c), m) =>
      tpe(m) match {
        case "bold" => (o :+ "<strong>", "</strong>" :: c)
        case "italic" => (o :+ "<em>", "</em>" :: c)
        case "strike" => (o :+ "<s>", "</s>" :: c)
        case "underline" => (o :+ "<u>", "</u>" :: c)
        case "code" => (o :+ "<code>", "</code>" :: c)
        case "highlight" => (o :+ "<mark>", "</mark>" :: c)
        case "link" =>
          m.hcursor.downField("attrs").get[String]("href").toOption.flatMap(safeUrl) match {
            case Some(h) =>
              (o :+ s"""<a href="${Html.escape(h)}" target="_blank" rel="noopener noreferrer nofollow">""", "</a>" :: c)
            case None => (o, c)
          }
        case _ => (o, c)
      }
    }
    open.foreach(b.append)
    // リンクの付いていない URL（Enter で送ったときなど）もリンクにする（コードの中は除く）
    val linked = marks.exists(m => tpe(m) == "link" || tpe(m) == "code")
    if linked then b.append(t)
    else {
      var pos = 0
      UrlRe.findAllMatchIn(t).foreach { m =>
        b.append(t.substring(pos, m.start))
        val href = Html.unescape(m.matched)
        b.append(
          s"""<a href="${Html.escape(href)}" target="_blank" rel="noopener noreferrer nofollow">${m.matched}</a>"""
        )
        pos = m.end
      }
      b.append(t.substring(pos))
    }
    close.foreach(b.append)
  }

  /** 本文の中の URL（エスケープした後の文字列に使う。末尾の句読点・括弧は含めない）。 */
  private val UrlRe = """https?://[^\s<>"'）」』]+[^\s<>"'）」』.,;:!?、。)\]]""".r

  // ------------------------------------------------------------------ 平文・メンション

  /** 検索用の平文（ブロックの区切りは改行）。 */
  def toPlain(
    doc: Json
  ): String = plainBlocks(doc).trim

  private def plainOf(
    n: Json
  ): String =
    tpe(n) match {
      case "text" => n.hcursor.get[String]("text").getOrElse("")
      case "hardBreak" => "\n"
      case "mention" => "@" + attr(n, "label").as[String].getOrElse("")
      case "channelMention" => "#" + attr(n, "label").as[String].getOrElse("")
      case "image" => attr(n, "alt").as[String].getOrElse("")
      case _ => nodes(n.hcursor).map(plainOf).mkString
    }

  private val blockTypes = Set(
    "paragraph",
    "heading",
    "listItem",
    "taskItem",
    "codeBlock",
    "blockquote",
    "tableRow",
    "tableCell",
    "tableHeader"
  )

  private def plainBlocks(
    n: Json
  ): String = {
    val t = tpe(n)
    if t == "text" || t == "hardBreak" || t == "mention" || t == "image" || t == "channelMention" then plainOf(n)
    else {
      val inner = nodes(n.hcursor).map(plainBlocks).mkString
      if blockTypes(t) then (if t == "tableCell" || t == "tableHeader" then inner + "\t" else inner + "\n") else inner
    }
  }

  /** 文書に含まれるメンション（ユーザーID）。 */
  def mentions(
    doc: Json
  ): Set[UUID] = {
    def go(
      n: Json
    ): List[UUID] =
      (if tpe(n) == "mention" then attr(n, "id").as[String].toOption.flatMap(uuidOpt).toList else Nil) ++
        nodes(n.hcursor).flatMap(go)
    go(doc).toSet
  }

  /** 本文に埋め込んだ添付の画像の ID（src が /api/files/{ID}/…）。 */
  def fileIds(
    doc: Json
  ): Set[UUID] = {
    val Re = """^/api/files/([0-9a-fA-F-]{36})(?:/.*)?$""".r
    def go(
      n: Json
    ): List[UUID] =
      (if tpe(n) == "image" then
         attr(n, "src").as[String].toOption.collect { case Re(id) => id }.flatMap(uuidOpt).toList
       else Nil) ++
        nodes(n.hcursor).flatMap(go)
    go(doc).toSet
  }

  /** まとめてのメンションの宛先（"g:<グループID>"・"r:channel"（チャンネルの全員）・"r:here"）。 */
  def isGroupKey(
    k: String
  ): Boolean = (k.startsWith("g:") && uuidOpt(k.drop(2)).isDefined) || Set("r:channel", "r:here").contains(k)

  /** 文書に含まれる、まとめてのメンション。 */
  def groupMentions(
    doc: Json
  ): Set[String] = {
    def go(
      n: Json
    ): List[String] =
      (if tpe(n) == "mention" then attr(n, "id").as[String].toOption.filter(isGroupKey).toList else Nil) ++
        nodes(n.hcursor).flatMap(go)
    go(doc).toSet
  }

  /** 文書として最低限の形か（type が doc）。 */
  def valid(
    doc: Json
  ): Boolean = tpe(doc) == "doc" && withinLimits(doc)

  /** 入れ子の深さと要素の数の上限（深い入れ子で再帰の処理があふれないように）。 */
  val MaxDepth = 40
  val MaxNodes = 20000

  def withinLimits(
    doc: Json
  ): Boolean = {
    var count = 0
    // 再帰を使わずに数える（数える処理そのものがあふれないように）
    val stack = scala.collection.mutable.Stack[(Json, Int)]((doc, 1))
    var ok = true
    while ok && stack.nonEmpty do {
      val (n, d) = stack.pop()
      count += 1
      if d > MaxDepth || count > MaxNodes then ok = false
      else {
        nodes(n.hcursor).foreach(c => stack.push((c, d + 1)))
        n.hcursor.downField("marks").focus.flatMap(_.asArray).foreach(ms => count += ms.size)
      }
    }
    ok
  }

  /** 本文が空か（文字・画像・メンションがない）。 */
  def isBlank(
    doc: Json
  ): Boolean = {
    def go(
      n: Json
    ): Boolean = tpe(n) match {
      case "text" => n.hcursor.get[String]("text").getOrElse("").trim.isEmpty
      case "image" | "mention" | "horizontalRule" | "table" | "channelMention" => false
      case _ => nodes(n.hcursor).forall(go)
    }
    go(doc)
  }
}
