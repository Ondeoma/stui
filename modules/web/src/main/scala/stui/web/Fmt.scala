package stui.web

import stui.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  // 日時の表示（2026/11/15 09:30・「3分前」）は sbirds-lib
  export sbirds.web.Dates.{ago, dateTime}

  /** 2026-11-15 → 11/15(日). */
  def day(d: String): String = {
    val dt = new js.Date(d + "T00:00:00")
    val w = "日月火水木金土".charAt(dt.getDay().toInt)
    s"${dt.getMonth().toInt + 1}/${dt.getDate().toInt}($w)"
  }

  def today(): String = isoDate(new js.Date())

  /** 今の時刻（13:05）. */
  def clock(): String = { val d = new js.Date(); f"${d.getHours().toInt}%02d:${d.getMinutes().toInt}%02d" }

  def isoDate(d: js.Date): String =
    f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  def addDays(d: String, n: Int): String = {
    val dt = new js.Date(d + "T00:00:00")
    dt.setDate(dt.getDate() + n)
    isoDate(dt)
  }

  def initials(name: String): String = name.trim.take(1)

  def bytes(n: Long): String =
    if (n < 1024) s"$n B" else if (n < 1024 * 1024) f"${n / 1024.0}%.1f KB" else f"${n / 1024.0 / 1024.0}%.1f MB"

  /** 時刻だけ（13:05）. */
  def time(t: Timestamp): String = {
    val d = t.toDate
    f"${d.getHours().toInt}%02d:${d.getMinutes().toInt}%02d"
  }

  /** 日付の区切り（今日・昨日・11月15日(日)・2025年11月15日(土)）. */
  def dayLabel(t: Timestamp): String = {
    val d = t.toDate
    val today = new js.Date()
    val key = isoDate(d)
    val w = "日月火水木金土".charAt(d.getDay().toInt)
    if (key == isoDate(today)) "今日"
    else if (key == addDays(isoDate(today), -1)) "昨日"
    else if (d.getFullYear() == today.getFullYear()) s"${d.getMonth().toInt + 1}月${d.getDate().toInt}日($w)"
    else s"${d.getFullYear().toInt}年${d.getMonth().toInt + 1}月${d.getDate().toInt}日($w)"
  }

  def dayKey(t: Timestamp): String = isoDate(t.toDate)

  /** 定期メッセージのくり返し（「毎週 水曜日 09:00」）. */
  def schedule(x: ScheduledDto): String = {
    val when = x.frequency match {
      case Frequency.Once => x.runDate.getOrElse("")
      case Frequency.Weekly => Frequency.weekdays.find(w => x.weekday.contains(w._1)).map(_._2 + "曜日").getOrElse("")
      case Frequency.Monthly => x.monthday.fold("")(d => s"${d}日")
      case _ => ""
    }
    List(Frequency.label(x.frequency), when, x.runTime).filter(_.nonEmpty).mkString(" ")
  }

}
