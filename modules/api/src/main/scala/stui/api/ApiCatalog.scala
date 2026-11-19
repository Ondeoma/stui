package stui.api

/** REST API の一覧（MCP の list_api_endpoints と GET /api/endpoints）。 */
object ApiCatalog {
  final case class Endpoint(
    method: String,
    path: String,
    description: String)
  val all: List[Endpoint] = List(
    Endpoint("GET", "/api/me", "自分の情報"),
    Endpoint("PATCH", "/api/me", "自分の名前・タイムゾーンを変える {name?, timezone?}"),
    Endpoint("GET", "/api/me/notify-prefs", "通知の種類ごとのメール送信の設定"),
    Endpoint("PUT", "/api/me/notify-prefs", "通知のメール設定を変える [{kind, email}]"),
    Endpoint("GET", "/api/users?q=", "ユーザーを探す"),
    Endpoint("GET", "/api/channels", "参加しているチャンネル・DM（未読・メンションの数つき）"),
    Endpoint("GET", "/api/channels/browse?q=&archived=true", "参加できるチャンネルを探す"),
    Endpoint("POST", "/api/channels", "チャンネルを作る {name, kind: public|private, topic?, purpose?, memberIds}"),
    Endpoint("GET", "/api/channels/by-name/{name}", "名前でチャンネルを取得"),
    Endpoint("GET", "/api/channels/{channelId}", "チャンネルの情報"),
    Endpoint("PATCH", "/api/channels/{channelId}", "チャンネルを変える {name?, topic?, purpose?, archived?, autoJoin?}"),
    Endpoint("DELETE", "/api/channels/{channelId}", "チャンネルを削除する（システム管理者）"),
    Endpoint("POST", "/api/channels/{channelId}/join", "チャンネルに参加する"),
    Endpoint("POST", "/api/channels/{channelId}/leave", "チャンネルから退出する"),
    Endpoint("GET", "/api/channels/{channelId}/members", "メンバーの一覧"),
    Endpoint("POST", "/api/channels/{channelId}/members", "メンバーを追加する {userIds}"),
    Endpoint("DELETE", "/api/channels/{channelId}/members/{userId}", "メンバーを外す"),
    Endpoint("PUT", "/api/channels/{channelId}/members/{userId}/role", "チャンネルでの役割 {role: admin|member}"),
    Endpoint(
      "PATCH",
      "/api/channels/{channelId}/prefs",
      "自分の設定 {favorite?, muted?, notifyLevel?: default|all|mention|none, hidden?}"
    ),
    Endpoint("POST", "/api/channels/{channelId}/read", "既読にする {seq}（0 なら最新まで）"),
    Endpoint("POST", "/api/channels/{channelId}/unread", "そのメッセージから未読に戻す {seq}"),
    Endpoint("GET", "/api/channels/{channelId}/messages?before=&after=&around=&limit=", "メッセージ（seq で前後をたどる）"),
    Endpoint(
      "POST",
      "/api/channels/{channelId}/messages",
      "投稿する {body: Tiptap の文書 または Markdown の文字列, rootId?, alsoChannel?, attachmentIds?}"
    ),
    Endpoint("POST", "/api/channels/{channelId}/files", "ファイルを添付する（multipart/form-data の file。投稿の attachmentIds に入れる）"),
    Endpoint("GET", "/api/channels/{channelId}/pins", "ピン留めしたメッセージ"),
    Endpoint("GET", "/api/channels/{channelId}/bookmarks", "チャンネルのブックマーク（ヘッダーのリンク）"),
    Endpoint("POST", "/api/channels/{channelId}/bookmarks", "ブックマークを追加 {title, url, emoji?}"),
    Endpoint("DELETE", "/api/channels/{channelId}/bookmarks/{bookmarkId}", "ブックマークを削除"),
    Endpoint("POST", "/api/channels/{channelId}/meeting", "skaka に会議室を作って案内を投稿する {name?, rootId?}"),
    Endpoint("POST", "/api/dms", "DM を開く {userIds}（1人なら1対1、2人以上ならグループ DM）"),
    Endpoint("GET", "/api/messages/{messageId}", "メッセージ"),
    Endpoint("PUT", "/api/messages/{messageId}", "自分のメッセージを編集する {body}"),
    Endpoint("DELETE", "/api/messages/{messageId}", "メッセージを削除する"),
    Endpoint("GET", "/api/messages/{messageId}/thread", "スレッド（親と返信）"),
    Endpoint("POST", "/api/messages/{messageId}/replies", "スレッドに返信する {body, alsoChannel?}"),
    Endpoint(
      "POST",
      "/api/messages/{messageId}/reactions",
      "リアクション {emoji}（👍 ❤️ 😄 🎉 🙏 👀 🚀 ✅ など、またはカスタム絵文字 :name:）"
    ),
    Endpoint("DELETE", "/api/messages/{messageId}/reactions?emoji=", "リアクションを外す"),
    Endpoint("POST", "/api/messages/{messageId}/pin", "ピン留め（DELETE で外す）"),
    Endpoint("POST", "/api/messages/{messageId}/save", "保存（ブックマーク。DELETE で外す）"),
    Endpoint("POST", "/api/messages/{messageId}/follow", "スレッドをフォロー（DELETE でやめる）"),
    Endpoint("POST", "/api/messages/batch", "メッセージをまとめて読む {ids}"),
    Endpoint("POST", "/api/messages/lookup", "メッセージの概要をまとめて読む {ids}（連携サービス向け）"),
    Endpoint("GET", "/api/threads", "フォロー中のスレッド（未読の数つき）"),
    Endpoint("GET", "/api/saved", "保存したメッセージ"),
    Endpoint("GET", "/api/saved/count", "保存したメッセージの件数"),
    Endpoint("GET", "/api/drafts", "下書き"),
    Endpoint(
      "GET",
      "/api/search?q=&limit=&offset=",
      "メッセージの検索（in:#チャンネル from:@名前 with:@名前 has:link|file|image|reaction|pin|meeting is:thread|pinned|saved|dm before: after: on: during:2026-11 order:new|old）"
    ),
    Endpoint("GET", "/api/notifications?kinds=mention,dm&unread=true", "通知の一覧"),
    Endpoint("POST", "/api/notifications/read", "通知を既読にする {ids}"),
    Endpoint("POST", "/api/notifications/read-all", "通知をすべて既読にする {kinds?}"),
    Endpoint("GET", "/api/reminders?done=true", "リマインダー"),
    Endpoint("POST", "/api/reminders", "リマインダーを作る {text?, messageId?, remindAt: ISO 8601}"),
    Endpoint("PATCH", "/api/reminders/{reminderId}", "リマインダーを変える {remindAt?, done?}"),
    Endpoint("DELETE", "/api/reminders/{reminderId}", "リマインダーを削除"),
    Endpoint("GET", "/api/scheduled?channelId=", "定期メッセージ（channelId がなければ自分が作ったもの）"),
    Endpoint(
      "POST",
      "/api/scheduled",
      "定期メッセージ {channelId, body, frequency: once|daily|weekdays|weekly|monthly, runDate?, runTime: HH:mm, weekday?, monthday?, enabled}"
    ),
    Endpoint("PUT", "/api/scheduled/{scheduledId}", "定期メッセージを変える"),
    Endpoint("DELETE", "/api/scheduled/{scheduledId}", "定期メッセージを削除"),
    Endpoint("GET", "/api/hooks/incoming", "受信の Webhook（チャンネルの管理者）"),
    Endpoint(
      "POST",
      "/api/hooks/incoming",
      "受信の Webhook を作る {channelId, name, username?, iconUrl?, enabled}（応答の url に POST すると投稿される）"
    ),
    Endpoint("GET", "/api/hooks/outgoing", "送信の Webhook"),
    Endpoint("POST", "/api/hooks/outgoing", "送信の Webhook を作る {channelId?, name, url, events, triggerWords, enabled}"),
    Endpoint("POST", "/api/apps/notify", "連携サービスからの通知 {text, title?, url?, channel?}（OAuth で発行したトークン）"),
    Endpoint("GET", "/api/admin/users", "ユーザーの一覧（管理者）"),
    Endpoint("PATCH", "/api/admin/users/{userId}", "ユーザーを変える（管理者） {isAdmin?, disabled?}"),
    Endpoint("GET", "/api/admin/channels", "すべてのチャンネル（管理者）"),
    Endpoint("GET", "/api/admin/audit", "監査ログ（管理者）"),
    Endpoint("GET", "/api/admin/agents", "エージェント専用アカウントの一覧（管理者・画面からのみ。キーと参加チャンネルつき）"),
    Endpoint("POST", "/api/admin/agents", "エージェント専用アカウントを作る（管理者・画面からのみ） {name}"),
    Endpoint("PATCH", "/api/admin/agents/{agentId}", "エージェント専用アカウントを変える（管理者・画面からのみ） {name?, disabled?}"),
    Endpoint(
      "POST",
      "/api/admin/agents/{agentId}/keys",
      "エージェント専用アカウントの APIキーを発行（管理者・画面からのみ） {name, scopes: read|write, expiresInDays?}"
    ),
    Endpoint("DELETE", "/api/admin/agents/{agentId}/keys/{keyId}", "エージェント専用アカウントの APIキーを取り消す（管理者・画面からのみ）"),
    Endpoint("POST", "/api/admin/agents/{agentId}/channels", "エージェント専用アカウントをチャンネルに参加させる（管理者・画面からのみ） {channelIds}"),
    Endpoint("DELETE", "/api/admin/agents/{agentId}/channels/{channelId}", "エージェント専用アカウントをチャンネルから外す（管理者・画面からのみ）")
  )
}
