package stui.core

import java.time.LocalDate

/**
 * メッセージの検索欄の絞り込み構文。
 * 例: `in:#開発 from:@山田 has:link is:thread after:2026-10-01 before:-7d "障害 報告" -テスト`
 *
 *  - `キー:値`。先頭の `-` は否定（キーのない語にも使える）。
 *  - 値に空白を含めるときは "..." で囲む。キーのない語は全文検索（AND）。
 *  - 日付は `2026-12-01`、`today`、`yesterday`、`-7d`（7日前）、`-2w`、`-1m`。`during:` は `2026-11`（月）・`2026`（年）・日付。
 */
object SearchQuery {

  final case class Filter(
    key: String,
    value: String,
    negate: Boolean)
  final case class Query(
    filters: List[Filter],
    text: List[String],
    negText: List[String]) {
    def isEmpty: Boolean = filters.isEmpty && text.isEmpty && negText.isEmpty
    def values(
      key: String
    ): List[String] = filters.filter(f => f.key == key && !f.negate).map(_.value)
    def negValues(
      key: String
    ): List[String] = filters.filter(f => f.key == key && f.negate).map(_.value)
  }

  /** 使えるキー（別名を含む）。 */
  val keys: Map[String, String] = Map(
    "in" -> "in",
    "channel" -> "in",
    "チャンネル" -> "in",
    "from" -> "from",
    "送信者" -> "from",
    "投稿者" -> "from",
    "with" -> "with",
    "mention" -> "mentions",
    "mentions" -> "mentions",
    "メンション" -> "mentions",
    "has" -> "has",
    "is" -> "is",
    "before" -> "before",
    "前" -> "before",
    "after" -> "after",
    "後" -> "after",
    "on" -> "on",
    "日付" -> "on",
    "during" -> "during",
    "期間" -> "during",
    "order" -> "order",
    "sort" -> "order",
    "並び" -> "order"
  )

  val hasValues: Set[String] = Set("link", "file", "image", "reaction", "pin", "meeting", "thread", "mention")
  val isValues: Set[String] = Set("thread", "reply", "pinned", "saved", "bot", "dm", "unread")
  val orderValues: Set[String] = Set("new", "old", "relevance", "score")

  /** 字句に分ける（"..." は1語）。 */
  private[core] def tokens(
    s: String
  ): List[String] = {
    val out = List.newBuilder[String]
    val cur = new StringBuilder
    var inQ = false
    s.foreach { c =>
      if c == '"' then inQ = !inQ
      else if (c == ' ' || c == '　' || c == '\t' || c == '\n') && !inQ then {
        if cur.nonEmpty then { out += cur.toString; cur.clear() }
      } else cur.append(c)
    }
    if cur.nonEmpty then out += cur.toString
    out.result()
  }

  private val FilterRe = """^(-?)([^\s:]+?)[:：](.*)$""".r

  def parse(
    s: String
  ): Either[String, Query] = {
    val filters = List.newBuilder[Filter]
    val text = List.newBuilder[String]
    val neg = List.newBuilder[String]
    var error: Option[String] = None
    tokens(s).foreach {
      case t @ FilterRe(n, k, v) if keys.contains(k.toLowerCase) =>
        val key = keys(k.toLowerCase)
        val value = v.trim
        if value.isEmpty then error = error.orElse(Some(s"「$t」の値がありません"))
        else {
          val lv = value.toLowerCase
          key match {
            case "has" if !hasValues(lv) =>
              error = error.orElse(Some(s"has: に使えるのは ${hasValues.toList.sorted.mkString("・")} です"))
            case "is" if !isValues(lv) =>
              error = error.orElse(Some(s"is: に使えるのは ${isValues.toList.sorted.mkString("・")} です"))
            case "order" if !orderValues(lv) => error = error.orElse(Some("order: に使えるのは new・old・relevance です"))
            case "has" | "is" | "order" => filters += Filter(key, lv, n == "-")
            case "in" => filters += Filter(key, value.stripPrefix("#"), n == "-")
            case "from" | "with" | "mentions" => filters += Filter(key, value.stripPrefix("@"), n == "-")
            case _ => filters += Filter(key, value, n == "-")
          }
        }
      case t =>
        if t.startsWith("-") && t.length > 1 then neg += t.drop(1)
        else text += t
    }
    error.toLeft(Query(filters.result(), text.result(), neg.result()))
  }

  /** 日付の値（today、-7d、-2w、-1m、2026-12-01）。 */
  def date(
    v: String,
    today: LocalDate
  ): Option[LocalDate] = {
    val t = v.trim.toLowerCase
    if t == "today" || t == "今日" then Some(today)
    else if t == "yesterday" || t == "昨日" then Some(today.minusDays(1))
    else {
      val re = """^([+-]?)(\d+)([dwmy])$""".r
      t match {
        case re(sign, n, unit) =>
          val k = n.toLong * (if sign == "+" then 1 else -1)
          Some(unit match {
            case "d" => today.plusDays(k)
            case "w" => today.plusWeeks(k)
            case "m" => today.plusMonths(k)
            case _ => today.plusYears(k)
          })
        case _ => scala.util.Try(LocalDate.parse(t)).toOption
      }
    }
  }

  /** during: の期間 [from, to)。`2026-11`（月）・`2026`（年）・日付・today・yesterday・-7d（その日から今日まで）。 */
  def period(
    v: String,
    today: LocalDate
  ): Option[(LocalDate, LocalDate)] = {
    val t = v.trim.toLowerCase
    val month = """^(\d{4})-(\d{1,2})$""".r
    val year = """^(\d{4})$""".r
    t match {
      case month(y, m) if m.toInt >= 1 && m.toInt <= 12 =>
        val s = LocalDate.of(y.toInt, m.toInt, 1)
        Some(s -> s.plusMonths(1))
      case year(y) => Some(LocalDate.of(y.toInt, 1, 1) -> LocalDate.of(y.toInt + 1, 1, 1))
      case "week" | "今週" =>
        val s = today.minusDays(today.getDayOfWeek.getValue - 1L)
        Some(s -> s.plusDays(7))
      case "month" | "今月" => Some(today.withDayOfMonth(1) -> today.withDayOfMonth(1).plusMonths(1))
      case x if x.startsWith("-") => date(x, today).map(_ -> today.plusDays(1))
      case x => date(x, today).map(d => d -> d.plusDays(1))
    }
  }

  /** 1つの語を PGroonga のクエリ構文のフレーズにする（構文の注入を防ぐ）。 */
  def phrase(
    w: String
  ): String = "\"" + w.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
