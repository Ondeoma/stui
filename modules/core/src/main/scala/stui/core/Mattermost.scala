package stui.core

import io.circe.Json
import stui.shared.{EmojiNames, Reactions}

import java.util.UUID

/** Mattermost から取り込むときの変換（投稿の本文・リアクションの絵文字・表示名）。DB を使わない部分。 */
object Mattermost {

  /** 取り込んだユーザーの sub の先頭（同じメールアドレスで最初にログインしたとき、その人のアカウントとして引き継ぐ）。 */
  val SubPrefix = "mattermost:"

  /** 表示名: ニックネーム → 姓名 → ユーザー名。 */
  def displayName(username: String, first: String, last: String, nickname: String): String = {
    val full = List(first.trim, last.trim).filter(_.nonEmpty).mkString(" ")
    List(nickname.trim, full, username.trim).find(_.nonEmpty).getOrElse("(名前なし)").take(80)
  }

  /** チャンネルの種類: O 公開・P 非公開・D 1対1・G グループ。 */
  def channelKind(tpe: String): Option[String] = tpe match {
    case "O" => Some("public")
    case "P" => Some("private")
    case "D" => Some("dm")
    case "G" => Some("group")
    case _ => None
  }

  /** 取り込む投稿か（参加・退出・ヘッダーの変更などのシステムの投稿は取り込まない）。 */
  def importable(tpe: String): Boolean = !tpe.startsWith("system_")

  /** 投稿の本文（Markdown）と props（Webhook などの attachments）を文書にする。@ユーザー名 はメンションにする。 */
  def body(message: String, props: Json, users: String => Option[(UUID, String)]): Json = {
    val md = SlackPayload.toMarkdown(Json.obj(
      "text" -> Json.fromString(message),
      "attachments" -> props.hcursor.downField("attachments").focus.getOrElse(Json.arr())
    ))
    mentions(RichText.fromMarkdown(md), users)
  }

  private val MentionRe = """(?<![\w@./-])@([A-Za-z0-9._-]+)""".r

  /** 文中の @ユーザー名（コードの中を除く）をメンションのノードにする。@channel・@all・@here はまとめてのメンション。 */
  def mentions(doc: Json, users: String => Option[(UUID, String)]): Json = {
    def mention(id: String, label: String) =
      Json.obj(
        "type" -> Json.fromString("mention"),
        "attrs" -> Json.obj("id" -> Json.fromString(id), "label" -> Json.fromString(label))
      )
    // 末尾の記号（文の終わりの . など）を削りながら、知っている名前を探す
    def resolve(name: String): Option[(Json, String)] =
      Iterator.iterate(name)(_.dropRight(1)).takeWhile(_.nonEmpty).takeWhile(n =>
        n == name || ".-_".contains(name(n.length))
      ).collectFirst {
        case n if Set("channel", "all").contains(n.toLowerCase) =>
          (mention("r:channel", "channel"), name.drop(n.length))
        case n if n.equalsIgnoreCase("here") => (mention("r:here", "here"), name.drop(n.length))
        case n if users(n.toLowerCase).isDefined =>
          val (id, label) = users(n.toLowerCase).get
          (mention(id.toString, label), name.drop(n.length))
      }
    def text(n: Json): List[Json] = {
      val c = n.hcursor
      val t = c.get[String]("text").getOrElse("")
      val isCode =
        c.downField("marks").focus.flatMap(_.asArray).exists(_.exists(_.hcursor.get[String]("type").contains("code")))
      if isCode || !t.contains('@') then List(n)
      else {
        val out = List.newBuilder[Json]
        var pos = 0
        def plain(s: String) = if s.nonEmpty then out += n.mapObject(_.add("text", Json.fromString(s)))
        MentionRe.findAllMatchIn(t).foreach { m =>
          resolve(m.group(1)).foreach {
            (node, rest) =>
              plain(t.substring(pos, m.start))
              out += node
              plain(rest)
              pos = m.end
          }
        }
        plain(t.substring(pos))
        out.result()
      }
    }
    def go(n: Json): List[Json] = {
      val c = n.hcursor
      c.get[String]("type").getOrElse("") match {
        case "text" => text(n)
        case "codeBlock" => List(n)
        case _ =>
          c.downField("content").focus.flatMap(_.asArray) match {
            case Some(kids) => List(n.mapObject(_.add("content", Json.fromValues(kids.toList.flatMap(go)))))
            case None => List(n)
          }
      }
    }
    go(doc).head
  }

  /** リアクションの絵文字。Mattermost の名前（thumbsup など）を stui で付けられる絵文字にする。ないものはカスタム絵文字（:名前:）として残す。 */
  def reaction(name: String): Option[String] = {
    val n = name.trim.toLowerCase
    EmojiNames.get(n).filter(Reactions.all.contains).orElse(Some(s":$n:").filter(Reactions.valid))
  }
}
