package stui.core

import io.circe.Json
import stui.shared.Reactions

import java.util.UUID

/** Mattermost から取り込むときの変換（投稿の本文・リアクションの絵文字・表示名）。DB を使わない部分。 */
object Mattermost:

  /** 取り込んだユーザーの sub の先頭（同じメールアドレスで最初にログインしたとき、その人のアカウントとして引き継ぐ）。 */
  val SubPrefix = "mattermost:"

  /** 表示名: ニックネーム → 姓名 → ユーザー名。 */
  def displayName(username: String, first: String, last: String, nickname: String): String =
    val full = List(first.trim, last.trim).filter(_.nonEmpty).mkString(" ")
    List(nickname.trim, full, username.trim).find(_.nonEmpty).getOrElse("(名前なし)").take(80)

  /** チャンネルの種類: O 公開・P 非公開・D 1対1・G グループ。 */
  def channelKind(tpe: String): Option[String] = tpe match
    case "O" => Some("public")
    case "P" => Some("private")
    case "D" => Some("dm")
    case "G" => Some("group")
    case _   => None

  /** 取り込む投稿か（参加・退出・ヘッダーの変更などのシステムの投稿は取り込まない）。 */
  def importable(tpe: String): Boolean = !tpe.startsWith("system_")

  /** 投稿の本文（Markdown）と props（Webhook などの attachments）を文書にする。@ユーザー名 はメンションにする。 */
  def body(message: String, props: Json, users: String => Option[(UUID, String)]): Json =
    val md = SlackPayload.toMarkdown(Json.obj("text" -> Json.fromString(message),
      "attachments" -> props.hcursor.downField("attachments").focus.getOrElse(Json.arr())))
    mentions(RichText.fromMarkdown(md), users)

  private val MentionRe = """(?<![\w@./-])@([A-Za-z0-9._-]+)""".r

  /** 文中の @ユーザー名（コードの中を除く）をメンションのノードにする。@channel・@all・@here はまとめてのメンション。 */
  def mentions(doc: Json, users: String => Option[(UUID, String)]): Json =
    def mention(id: String, label: String) =
      Json.obj("type" -> Json.fromString("mention"), "attrs" -> Json.obj("id" -> Json.fromString(id), "label" -> Json.fromString(label)))
    // 末尾の記号（文の終わりの . など）を削りながら、知っている名前を探す
    def resolve(name: String): Option[(Json, String)] =
      Iterator.iterate(name)(_.dropRight(1)).takeWhile(_.nonEmpty).takeWhile(n => n == name || ".-_".contains(name(n.length))).collectFirst {
        case n if Set("channel", "all").contains(n.toLowerCase) => (mention("r:channel", "channel"), name.drop(n.length))
        case n if n.equalsIgnoreCase("here")                    => (mention("r:here", "here"), name.drop(n.length))
        case n if users(n.toLowerCase).isDefined                 =>
          val (id, label) = users(n.toLowerCase).get
          (mention(id.toString, label), name.drop(n.length))
      }
    def text(n: Json): List[Json] =
      val c = n.hcursor
      val t = c.get[String]("text").getOrElse("")
      val isCode = c.downField("marks").focus.flatMap(_.asArray).exists(_.exists(_.hcursor.get[String]("type").contains("code")))
      if isCode || !t.contains('@') then List(n)
      else
        val out = List.newBuilder[Json]
        var pos = 0
        def plain(s: String) = if s.nonEmpty then out += n.mapObject(_.add("text", Json.fromString(s)))
        MentionRe.findAllMatchIn(t).foreach { m =>
          resolve(m.group(1)).foreach { (node, rest) =>
            plain(t.substring(pos, m.start))
            out += node
            plain(rest)
            pos = m.end
          }
        }
        plain(t.substring(pos))
        out.result()
    def go(n: Json): List[Json] =
      val c = n.hcursor
      c.get[String]("type").getOrElse("") match
        case "text"      => text(n)
        case "codeBlock" => List(n)
        case _ =>
          c.downField("content").focus.flatMap(_.asArray) match
            case Some(kids) => List(n.mapObject(_.add("content", Json.fromValues(kids.toList.flatMap(go)))))
            case None       => List(n)
    go(doc).head

  /** リアクションの絵文字。Mattermost の名前（thumbsup など）を stui で付けられる絵文字にする。ないものはカスタム絵文字（:名前:）として残す。 */
  def reaction(name: String): Option[String] =
    val n = name.trim.toLowerCase
    Emoji.get(n).filter(Reactions.all.contains).orElse(Some(s":$n:").filter(Reactions.valid))

  /** Mattermost（GitHub と同じ）の絵文字の名前 → 文字。stui の一覧にあるものだけが使われる。 */
  private val Emoji: Map[String, String] = Map(
    "+1" -> "👍", "thumbsup" -> "👍", "-1" -> "👎", "thumbsdown" -> "👎", "heart" -> "❤️", "smile" -> "😄", "tada" -> "🎉",
    "pray" -> "🙏", "eyes" -> "👀", "rocket" -> "🚀", "white_check_mark" -> "✅", "thinking" -> "🤔", "thinking_face" -> "🤔",
    "cry" -> "😢", "clap" -> "👏", "fire" -> "🔥", "100" -> "💯", "raised_hands" -> "🙌", "ok_hand" -> "👌", "sparkles" -> "✨",
    "grinning" -> "😀", "smiley" -> "😃", "grin" -> "😁", "laughing" -> "😆", "satisfied" -> "😆", "sweat_smile" -> "😅",
    "rofl" -> "🤣", "rolling_on_the_floor_laughing" -> "🤣", "joy" -> "😂", "slightly_smiling_face" -> "🙂", "wink" -> "😉",
    "blush" -> "😊", "innocent" -> "😇", "smiling_face_with_three_hearts" -> "🥰", "heart_eyes" -> "😍", "star_struck" -> "🤩",
    "kissing_heart" -> "😘", "yum" -> "😋", "stuck_out_tongue" -> "😛", "stuck_out_tongue_winking_eye" -> "😜", "zany_face" -> "🤪",
    "hugs" -> "🤗", "hugging_face" -> "🤗", "face_with_hand_over_mouth" -> "🤭", "shushing_face" -> "🤫", "zipper_mouth_face" -> "🤐",
    "neutral_face" -> "😐", "expressionless" -> "😑", "no_mouth" -> "😶", "smirk" -> "😏", "unamused" -> "😒", "roll_eyes" -> "🙄",
    "face_with_rolling_eyes" -> "🙄", "grimacing" -> "😬", "relieved" -> "😌", "pensive" -> "😔", "sleepy" -> "😪", "sleeping" -> "😴",
    "mask" -> "😷", "face_with_thermometer" -> "🤒", "hot_face" -> "🥵", "cold_face" -> "🥶", "dizzy_face" -> "😵",
    "exploding_head" -> "🤯", "partying_face" -> "🥳", "sunglasses" -> "😎", "nerd_face" -> "🤓", "monocle_face" -> "🧐",
    "confused" -> "😕", "worried" -> "😟", "slightly_frowning_face" -> "🙁", "open_mouth" -> "😮", "hushed" -> "😯",
    "astonished" -> "😲", "flushed" -> "😳", "pleading_face" -> "🥺", "frowning" -> "😦", "fearful" -> "😨", "cold_sweat" -> "😰",
    "disappointed_relieved" -> "😥", "sob" -> "😭", "scream" -> "😱", "confounded" -> "😖", "persevere" -> "😣",
    "disappointed" -> "😞", "sweat" -> "😓", "weary" -> "😩", "tired_face" -> "😫", "yawning_face" -> "🥱", "triumph" -> "😤",
    "rage" -> "😡", "pout" -> "😡", "angry" -> "😠", "cursing_face" -> "🤬", "smiling_imp" -> "😈", "skull" -> "💀",
    "clown_face" -> "🤡", "ghost" -> "👻", "robot" -> "🤖", "robot_face" -> "🤖",
    "wave" -> "👋", "raised_hand" -> "✋", "hand" -> "✋", "raised_hand_with_fingers_splayed" -> "🖐️", "pinched_fingers" -> "🤌",
    "v" -> "✌️", "crossed_fingers" -> "🤞", "love_you_gesture" -> "🤟", "metal" -> "🤘", "call_me_hand" -> "🤙",
    "point_left" -> "👈", "point_right" -> "👉", "point_up_2" -> "👆", "point_down" -> "👇", "point_up" -> "☝️", "fist" -> "✊",
    "fist_raised" -> "✊", "facepunch" -> "👊", "punch" -> "👊", "fist_oncoming" -> "👊", "fist_left" -> "🤛", "fist_right" -> "🤜",
    "open_hands" -> "👐", "palms_up_together" -> "🤲", "handshake" -> "🤝", "writing_hand" -> "✍️", "muscle" -> "💪",
    "saluting_face" -> "🫡", "heart_hands" -> "🫶",
    "orange_heart" -> "🧡", "yellow_heart" -> "💛", "green_heart" -> "💚", "blue_heart" -> "💙", "purple_heart" -> "💜",
    "black_heart" -> "🖤", "white_heart" -> "🤍", "broken_heart" -> "💔", "heavy_heart_exclamation" -> "❣️", "two_hearts" -> "💕",
    "anger" -> "💢", "boom" -> "💥", "collision" -> "💥", "dizzy" -> "💫", "sweat_drops" -> "💦", "dash" -> "💨",
    "speech_balloon" -> "💬", "thought_balloon" -> "💭", "zzz" -> "💤", "ballot_box_with_check" -> "☑️", "heavy_check_mark" -> "✔️",
    "x" -> "❌", "negative_squared_cross_mark" -> "❎", "o" -> "⭕", "exclamation" -> "❗", "heavy_exclamation_mark" -> "❗",
    "question" -> "❓", "interrobang" -> "⁉️", "bangbang" -> "‼️", "warning" -> "⚠️", "no_entry_sign" -> "🚫", "no_entry" -> "⛔",
    "ok" -> "🆗", "new" -> "🆕", "up" -> "🆙", "red_circle" -> "🔴", "large_orange_circle" -> "🟠", "orange_circle" -> "🟠",
    "large_yellow_circle" -> "🟡", "yellow_circle" -> "🟡", "large_green_circle" -> "🟢", "green_circle" -> "🟢",
    "large_blue_circle" -> "🔵", "blue_circle" -> "🔵", "large_purple_circle" -> "🟣", "purple_circle" -> "🟣",
    "arrow_up" -> "⬆️", "arrow_down" -> "⬇️", "arrow_right" -> "➡️", "arrow_left" -> "⬅️", "repeat" -> "🔁",
    "heavy_plus_sign" -> "➕", "heavy_minus_sign" -> "➖",
    "confetti_ball" -> "🎊", "balloon" -> "🎈", "gift" -> "🎁", "trophy" -> "🏆", "1st_place_medal" -> "🥇", "medal_sports" -> "🏅",
    "star" -> "⭐", "star2" -> "🌟", "zap" -> "⚡", "bulb" -> "💡", "pushpin" -> "📌", "paperclip" -> "📎", "memo" -> "📝",
    "pencil" -> "📝", "date" -> "📅", "alarm_clock" -> "⏰", "hourglass_flowing_sand" -> "⏳", "lock" -> "🔒", "key" -> "🔑",
    "hammer_and_wrench" -> "🛠️", "wrench" -> "🔧", "gear" -> "⚙️", "test_tube" -> "🧪", "bug" -> "🐛", "flying_saucer" -> "🛸",
    "computer" -> "💻", "iphone" -> "📱", "package" -> "📦", "chart_with_upwards_trend" -> "📈", "chart_with_downwards_trend" -> "📉",
    "bar_chart" -> "📊", "moneybag" -> "💰", "coffee" -> "☕", "tea" -> "🍵", "beer" -> "🍺", "sushi" -> "🍣", "rice_ball" -> "🍙",
    "cake" -> "🍰", "pizza" -> "🍕",
    "cherry_blossom" -> "🌸", "sunflower" -> "🌻", "four_leaf_clover" -> "🍀", "seedling" -> "🌱", "rainbow" -> "🌈",
    "sunny" -> "☀️", "crescent_moon" -> "🌙", "partly_sunny" -> "⛅", "umbrella" -> "☔", "snowflake" -> "❄️", "ocean" -> "🌊",
    "dog" -> "🐶", "cat" -> "🐱", "panda_face" -> "🐼", "penguin" -> "🐧", "turtle" -> "🐢", "unicorn" -> "🦄", "unicorn_face" -> "🦄",
    "bee" -> "🐝", "honeybee" -> "🐝", "butterfly" -> "🦋"
  )
