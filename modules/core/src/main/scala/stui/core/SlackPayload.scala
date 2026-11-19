package stui.core

import cats.syntax.all.*
import io.circe.{ACursor, Json}

/** Slack・Mattermost の形のメッセージ（text と attachments）。受信の Webhook と Mattermost からの取り込みで使う。 */
object SlackPayload {

  private def str(c: ACursor, k: String): Option[String] = c.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  /** Markdown にする（attachments は見出し・本文・項目を並べる）。 */
  def toMarkdown(j: Json): String = {
    val c = j.hcursor
    val atts = c.downField("attachments").focus.flatMap(_.asArray).toList.flatten.map { a =>
      val ac = a.hcursor
      val title = (str(ac, "title"), str(ac, "title_link")) match {
        case (Some(t), Some(l)) => Some(s"**[$t]($l)**")
        case (Some(t), None) => Some(s"**$t**")
        case _ => None
      }
      val fields = ac.downField("fields").focus.flatMap(_.asArray).toList.flatten.flatMap(f =>
        (str(f.hcursor, "title"), str(f.hcursor, "value")).mapN((t, v) => s"- **$t**: $v")
      )
      (List(
        str(ac, "pretext"),
        str(ac, "author_name"),
        title,
        str(ac, "text").orElse(if title.isEmpty then str(ac, "fallback") else None)
      ).flatten ++
        fields ++ str(ac, "footer").toList).mkString("\n\n")
    }
    (str(c, "text").toList ++ atts).mkString("\n\n")
  }
}
