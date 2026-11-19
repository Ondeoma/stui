package stui.shared

/** 絵文字の名前（Slack・Mattermost・GitHub と同じ :name: の name）→ 絵文字の文字。
  * Mattermost の取り込みのリアクションと、受信 Webhook のアイコン（icon_emoji）で使う。
  */
object EmojiNames {
  private val names: Map[String, String] = Map(
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

  /** 名前（前後の : は付けない）から絵文字の文字。 */
  def get(name: String): Option[String] = names.get(name.trim.toLowerCase)

  /** ":name:" の形なら、絵文字の文字にする。知らない名前・その他の形は None。 */
  def fromShortcode(s: String): Option[String] = {
    val t = s.trim
    if (t.length > 2 && t.startsWith(":") && t.endsWith(":")) get(t.substring(1, t.length - 1)) else None
  }
}
