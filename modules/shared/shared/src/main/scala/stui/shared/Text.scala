package stui.shared

// バックエンドと画面の両方で使う文字列の処理（同じ規則を2か所に書かないため、ここにまとめる）.

/** 画面の URL（サーバーの通知・メールと画面で同じ形を使う）. */
object Paths {
  def channel(channelId: java.util.UUID): String = s"/c/$channelId"
  def thread(channelId: java.util.UUID, rootId: java.util.UUID): String = s"/c/$channelId/t/$rootId"

  /** そのメッセージを開く（スレッドの返信ならスレッドを開く）. */
  def message(channelId: java.util.UUID, messageId: Option[java.util.UUID], rootId: Option[java.util.UUID]): String =
    rootId.fold(channel(channelId))(thread(channelId, _)) + messageId.fold("")(m => s"?m=$m")
}

/** ファイル名・種類から選ぶアイコン（サーバーのリンクの名前と画面の添付で同じものを使う）. */
object FileIcons {
  def of(name: String, folder: Boolean = false, contentType: String = ""): String =
    if (folder) "📁"
    else {
      val ext = name.split('.').toList match {
        case _ :: rest if rest.nonEmpty => rest.last.toLowerCase
        case _ => ""
      }
      ext match {
        case "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" | "bmp" | "heic" => "🖼️"
        case "pdf" => "📕"
        case "doc" | "docx" | "odt" | "rtf" => "📝"
        case "xls" | "xlsx" | "ods" | "csv" => "📊"
        case "ppt" | "pptx" | "odp" => "📽️"
        case "mp4" | "mov" | "webm" | "mkv" | "avi" => "🎬"
        case "mp3" | "wav" | "m4a" | "ogg" | "flac" => "🎵"
        case "zip" | "gz" | "tgz" | "7z" | "rar" | "tar" => "🗜️"
        case _ if contentType.startsWith("video/") => "🎬"
        case _ if contentType.startsWith("audio/") => "🎵"
        case _ if contentType.startsWith("image/") => "🖼️"
        case _ => "📄"
      }
    }
}

/** HTML のエスケープ. */
object Html {

  def escape(s: String): String = {
    val b = new StringBuilder(s.length + 16)
    s.foreach {
      case '<' => b.append("&lt;")
      case '>' => b.append("&gt;")
      case '&' => b.append("&amp;")
      case '"' => b.append("&quot;")
      case '\'' => b.append("&#39;")
      case c => b.append(c)
    }
    b.toString
  }

  /** HTML のタグを除いた文字（一覧の短い表示用）. */
  def text(html: String): String = unescape(html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim)

  def unescape(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

}
