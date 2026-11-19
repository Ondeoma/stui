package stui.core

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime, ZoneId}

/** 定期メッセージの次の送信日時の計算。 */
object Recurrence {

  val Frequencies: Set[String] = Set("once", "daily", "weekdays", "weekly", "monthly")

  def parseTime(
    s: String
  ): Option[LocalTime] =
    s.trim match {
      case s"$h:$m" => (h.toIntOption, m.toIntOption) match {
          case (Some(hh), Some(mm)) if hh >= 0 && hh < 24 && mm >= 0 && mm < 60 => Some(LocalTime.of(hh, mm))
          case _ => None
        }
      case _ => None
    }

  /** その日に送るか。 */
  private def matches(
    freq: String,
    d: LocalDate,
    weekday: Option[Int],
    monthday: Option[Int]
  ): Boolean = freq match {
    case "daily" => true
    case "weekdays" => d.getDayOfWeek != DayOfWeek.SATURDAY && d.getDayOfWeek != DayOfWeek.SUNDAY
    case "weekly" => d.getDayOfWeek.getValue == weekday.filter(w => w >= 1 && w <= 7).getOrElse(1)
    case "monthly" =>
      val md = monthday.filter(x => x >= 1 && x <= 31).getOrElse(1)
      d.getDayOfMonth == math.min(md, d.lengthOfMonth)
    case _ => false
  }

  /**
   * after より後で最初の送信日時。1回だけ（once）は runDate の runTime が after より後ならその日時、過ぎていれば None。
   * 夏時間で存在しない時刻は、その日の次の有効な時刻にずれる（ZonedDateTime の規則）。
   */
  def next(
    freq: String,
    runDate: Option[LocalDate],
    runTime: LocalTime,
    weekday: Option[Int],
    monthday: Option[Int],
    zone: ZoneId,
    after: Instant
  ): Option[Instant] =
    if freq == "once" then runDate.map(_.atTime(runTime).atZone(zone).toInstant).filter(_.isAfter(after))
    else {
      val start = after.atZone(zone).toLocalDate
      Iterator.iterate(start)(_.plusDays(1)).take(400)
        .filter(d => matches(freq, d, weekday, monthday))
        .map(_.atTime(runTime).atZone(zone).toInstant)
        .find(_.isAfter(after))
    }
}
