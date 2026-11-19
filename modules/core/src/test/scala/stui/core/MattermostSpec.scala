package stui.core

import io.circe.Json

import java.util.UUID

class MattermostSpec extends munit.FunSuite {

  private val alice = UUID.randomUUID()
  private val users = Map("alice" -> (alice, "山田 アリス"), "bob.smith" -> (UUID.randomUUID(), "Bob"))

  private def nodes(
    doc: Json
  ): List[Json] =
    doc :: doc.hcursor.downField("content").focus.flatMap(_.asArray).toList.flatten.flatMap(nodes)

  private def mentions(
    doc: Json
  ): List[(String, String)] =
    nodes(doc).filter(_.hcursor.get[String]("type").contains("mention"))
      .map(n =>
        (
          n.hcursor.downField("attrs").get[String]("id").getOrElse(""),
          n.hcursor.downField("attrs").get[String]("label").getOrElse("")
        )
      )

  test("@ユーザー名 をメンションにする（文の終わりの記号・知らない名前・コードの中はそのまま）") {
    val doc = Mattermost.body(
      "こんにちは @alice さん、@bob.smith. と @nobody へ。`@alice` は書き方の例\n\n```\n@alice\n```",
      Json.obj(),
      users.get
    )
    assertEquals(mentions(doc), List((alice.toString, "山田 アリス"), (users("bob.smith")._1.toString, "Bob")))
    assert(RichText.toPlain(doc).contains("@nobody"))
    assert(RichText.toPlain(doc).contains("Bob."), RichText.toPlain(doc))
  }

  test("@channel・@all・@here はまとめてのメンション") {
    val doc = Mattermost.body("@channel と @all と @here", Json.obj(), _ => None)
    assertEquals(mentions(doc).map(_._1), List("r:channel", "r:channel", "r:here"))
  }

  test("Webhook の attachments も本文にする") {
    val props = Json.obj("attachments" ->
      Json.arr(Json.obj("title" -> Json.fromString("ビルド"), "text" -> Json.fromString("成功しました"))))
    assertEquals(
      RichText.toPlain(Mattermost.body("CI", props, _ => None)).split("\n").toList.map(_.trim).filter(_.nonEmpty),
      List("CI", "ビルド", "成功しました")
    )
  }

  test("リアクションの絵文字の名前を文字にする（ないものはカスタム絵文字の名前）") {
    assertEquals(Mattermost.reaction("+1"), Some("👍"))
    assertEquals(Mattermost.reaction("white_check_mark"), Some("✅"))
    assertEquals(Mattermost.reaction("partyparrot"), Some(":partyparrot:"))
    assertEquals(Mattermost.reaction("x"), Some("❌"))
    assertEquals(Mattermost.reaction("日本語"), None)
  }

  test("表示名・チャンネルの種類・取り込む投稿") {
    assertEquals(Mattermost.displayName("taro", "太郎", "山田", ""), "太郎 山田")
    assertEquals(Mattermost.displayName("taro", "", "", "たろう"), "たろう")
    assertEquals(Mattermost.displayName("taro", "", "", ""), "taro")
    assertEquals(
      List("O", "P", "D", "G", "X").map(Mattermost.channelKind),
      List(Some("public"), Some("private"), Some("dm"), Some("group"), None)
    )
    assert(Mattermost.importable(""))
    assert(!Mattermost.importable("system_join_channel"))
  }
}
