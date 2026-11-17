package stui.shared

import io.circe.{Decoder, Encoder}

import scala.scalajs.js

/**
 * API の日時（ISO 8601 の文字列）. 画面(Scala.js)では java.time を使わない
 * （scala-java-time は出力が大きいため. 表示は js.Date で行う）.
 */
final case class Timestamp(iso: String) {

  def toDate: js.Date = new js.Date(iso)

  def toEpochMilli: Double = js.Date.parse(iso)

}

object Timestamp {

  given Encoder[Timestamp] = Encoder.encodeString.contramap(_.iso)
  given Decoder[Timestamp] = Decoder.decodeString.map(Timestamp(_))
  given Ordering[Timestamp] = Ordering.by(_.toEpochMilli)

}
