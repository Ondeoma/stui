# stui の設計

stui はチームのためのコミュニケーションツールです（チャンネル・スレッド・DM・リマインダー・定期メッセージ・検索）。
兄弟プロジェクト [skiwi](https://github.com/Ondeoma/skiwi)・[skea](https://github.com/Ondeoma/skea) と同じ技術・同じ運用方針で作ります。

## 1. 要件サマリ

| 区分 | 内容 |
|---|---|
| テナント | シングルテナント（1つの組織で1つの stui） |
| 認証 | OIDC（Authorization Code + PKCE）。Cookie のセッション、ユーザー単位のスコープ付き API キー |
| 権限 | システム権限（管理者・一般）と、チャンネルの権限（メンバー・管理者）。公開チャンネルは誰でも読めて参加できる。非公開チャンネル・DM はメンバーだけ |
| データ | チャンネル（公開・非公開）・DM（1対1・グループ）→ メッセージ（スレッドの返信） |
| 画面 | サイドバー（スレッド・アクティビティ・保存済み・下書き・リマインダー・お気に入り・チャンネル・DM）、チャンネル（右にスレッド・ピン留め・メンバー）、検索、個人設定、管理 |
| メッセージ | WYSIWYG（Tiptap）。@メンション（人・グループ・`@channel`・`@here`）、#チャンネル、絵文字のリアクション（カスタム絵文字）、添付、編集・削除、ピン留め、保存、リンクのコピー、未読に戻す、リマインド |
| 同期 | サーバーが変更を画面へ即座に知らせ（SSE）、画面は差分を取り直す（つながらないときは10秒ごとに確認） |
| 通知 | 画面内（メンション・DM・スレッド・チャンネル・リマインダー・連携サービス）、デスクトップ通知、メール（少し待って読まれなかったときだけ）。チャンネルごとの通知の設定・ミュート |
| リマインダー | メッセージまたは任意の内容を、指定の日時に stui のボットから DM で知らせる |
| 定期メッセージ | 1回だけ（送信の予約）・毎日・平日・毎週・毎月の決まった時刻に、作った人として投稿する |
| Webhook | 受信（Slack・Mattermost と同じ形の JSON でチャンネルに投稿）と送信（イベントを POST。応答の text で返信） |
| 検索 | PGroonga。日本語の部分一致、かな・全角半角の揺れ、タイプミスの許容、絞り込みの構文（`in:` `from:` `has:` `is:` `before:` など） |
| 連携 | 兄弟のサービスと OAuth: 通知を受ける・リンクの名前を出す・skaka の会議室を作る（[integrations.md](integrations.md)） |
| AI | MCP（Model Context Protocol）。ユーザーが画面でできることはすべて API・MCP からもできる |
| 基盤 | PostgreSQL（Patroni で冗長化）、S3 互換ストレージ、アプリは複数ノードで動かせるステートレス構成 |
| 実装 | Scala 3.3 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway。画面は Scalactive（Scala.js）。リッチテキストは Tiptap |
| その他 | レスポンシブ対応、ダークモード、MIT ライセンス |

## 2. 全体アーキテクチャ

```
        Browser (Scalactive SPA + Tiptap)
           │ HTTPS（REST / SSE / MCP / 受信 Webhook）
   ┌───────▼──────────┐   VIP(keepalived) + HAProxy
   │ node1 node2 node3 │ ← 各ノードで同一構成
   │  stui-app (Scala) │ http4s: 画面配信 / REST / SSE / MCP / Webhook 受信
   │  Patroni + PG     │ PostgreSQL + PGroonga
   │  etcd             │ Patroni のコンセンサス
   │  S3互換ストレージ   │ 添付ファイル・アイコン・カスタム絵文字
   └───────────────────┘
        ▲ OIDC          ▲ Webhook（受信・送信）     ▲ OAuth（通知・名前の問い合わせ・会議室）
       IdP             外部のシステム               skea・skiwi・skawau・skuaka・skaka
```

- アプリはステートレスです。状態はすべて PostgreSQL と S3 互換ストレージに置きます（セッションも DB）。
- 画面の同期は「SSE の知らせ + 差分の取得」です。各ノードは0.5秒ごとに DB の新しい変更履歴（`chat_event`）と通知を確かめ、
  そのノードにつないでいる画面のうち、そのチャンネルのメンバー（と、参加せずに見ている公開チャンネル）だけに知らせます。
  ノード間の連絡や振り分けの親和性は不要で、ロードバランサーは単純なラウンドロビンで済みます。
- バックグラウンド処理（メール・送信の Webhook・リマインダー・定期メッセージ）は全ノードで動かし、
  対象行を `FOR UPDATE SKIP LOCKED` で取り合うため、二重に送りません。掃除は `job_lock`（期限付きのリース）で1ノードだけが実行します。

## 3. データモデル（PostgreSQL）

主なテーブル（詳細は `modules/db/src/main/resources/db/migration`）。

- `app_user(id, sub, name, email, avatar_url, avatar_key, is_admin, is_bot, disabled, timezone, …)` … ボット（`sub = bot:…`）は Webhook・連携サービス・リマインダーの発言者
- `user_group`・`group_member` … IdP の groups クレームから同期（まとめてのメンション・`ADMIN_GROUPS`）
- `session`・`login_state`・`api_key(…, scopes, oauth_client)`
- `channel(id, name, kind, topic, purpose, dm_key, archived, auto_join, created_by, last_message_at)`
  - `kind`: `public`・`private`・`dm`（1対1・自分だけ）・`group`（3人以上の DM）。DM は参加者の ID を並べた `dm_key` で同じ顔ぶれを1つにする。
  - `auto_join`: 新しいユーザーを自動で参加させる（最初のユーザーがログインすると `#general` を作る）。
- `channel_member(channel_id, user_id, role, notify, muted, favorite, hidden, last_read_seq)` … 未読は `message.seq > last_read_seq` の数
- `channel_bookmark(channel_id, title, url, emoji, position)` … ヘッダーに並べるリンク
- `message(id, seq, channel_id, root_id, also_channel, author_id, kind, via, body jsonb, body_text, props jsonb, reply_count, last_reply_at,
          pinned_at, created_at, edited_at, deleted_at)`
  - `seq` は全体の通し番号（並び順・未読・画面の差分）。`root_id` があればスレッドの返信（`also_channel` ならチャンネルにも出す）。
  - `kind`: `user`・`system`（参加・退出・トピックの変更・ピン留め）・`bot`。`props` に会議（`meeting`）・Webhook の表示名（`username`・`iconUrl`）など。
  - 削除は `deleted_at`（返信が残っていれば「削除されました」として一覧に残す）。
- `reaction(message_id, user_id, emoji)`、`saved_message(user_id, message_id)`、`thread_follow(root_id, user_id, following, last_read_seq)`
- `attachment(id, channel_id, message_id, s3_key, file_name, size, content_type, sha256, inline)` … 投稿の前にアップロードし、投稿で結び付ける（1日たっても投稿されなければ消す）。
  `inline` は本文に埋め込んだ画像（添付の一覧には出さない）
- `message_draft(user_id, channel_id, root_id, body)` … 入力中の下書き（チャンネル＋スレッドごと）
- `chat_event(id bigserial, channel_id, kind, message_id, root_id, actor_id, user_id, at)` … 画面の同期（14日で消す）
- `reminder(user_id, text, message_id, remind_at, fired_at, done_at)`
- `scheduled_message(channel_id, author_id, root_id, body, frequency, run_date, run_time, weekday, monthday, timezone, next_run, enabled, last_error)`
- `notification(user_id, kind, channel_id, message_id, actor_id, title, body, read_at)`、`notify_pref`、`mail_outbox(…, notification_id)`
- `incoming_webhook(channel_id, name, token_hash, username, icon_url, icon_emoji)`、`outgoing_webhook(channel_id, url, secret, events, trigger_words)`、`webhook_delivery`
- `custom_emoji`、`oauth_code`、`integration_token`、`integration_state`、`job_lock`、`audit_log`

## 4. 認証・認可

- **ログイン**：OIDC（Authorization Code + PKCE）。ID token の `iss`/`aud`/`exp`/`nonce` を検証。開発環境では `DEV_LOGIN=true` で名前だけでログインできる（OIDC と同時には指定できず、https の `PUBLIC_URL` では起動しない）。
- **API キー**：`stui_` で始まるランダム値。ハッシュだけを保存。スコープは `read`・`write`・`admin`（管理者だけ）。キーでキーの発行・失効はできない。
- **チャンネル**：公開チャンネルはログインした人なら誰でも読め、参加できる（投稿・リアクションは参加してから）。非公開チャンネル・DM はメンバー以外には存在を見せない（404）。
  チャンネルの管理者（作った人・管理者にされた人）とシステム管理者が、名前・アーカイブ・メンバーの削除・Webhook を扱える。トピック・説明はメンバーなら誰でも変えられる。
  メッセージの編集は本人だけ、削除は本人・チャンネルの管理者・システム管理者。
- **兄弟のサービス**：OAuth 2.0 の認可コード + PKCE（S256 のみ）。[integrations.md](integrations.md)。

- **共通の守り**（`Guards`）：本文の上限（アップロード以外は 2MB）。Cookie のセッションで状態を変える要求は、Origin が別のサイトなら断る（同じ登録ドメインのほかのサービスからの CSRF も防ぐ）。
  OIDC の state はブラウザの Cookie にも入れて照合する（ログイン CSRF）。リッチテキストは入れ子の深さ（40）と要素の数（2万）を制限する。
- **送信の Webhook の宛先**：名前を引いた IP がループバック・プライベート・リンクローカルなどなら登録も送信も断る（SSRF。名前解決の結果は30秒使い回し、確かめた IP と接続する IP を同じにする（DNS rebinding）。開発だけ `WEBHOOK_ALLOW_PRIVATE=true`）。
  失敗の理由は、内部のネットワークの様子がわからない文言にして記録する。
- **セキュリティのヘッダー**：すべての応答に `X-Frame-Options: DENY`・`frame-ancestors 'none'`（クリックジャッキング）・`nosniff`・`Referrer-Policy`、https なら HSTS。
- **OAuth の許可**：`/oauth/authorize` は画面のログイン（Cookie）でだけ許可できる（API キーで同意を飛ばしてスコープを広げさせない）。使用済みの認可コードが再び出されたら、そのコードで発行したキーを失効させる。
- **SSE**：接続ごとに上限付きのキュー（あふれたら捨てる。読まない接続があってもほかの人への配信を止めない）、ユーザーごとに20本まで。1分ごとに認証を確かめ直し、ログアウト・無効化・失効の後は切る。
- **本文**：Markdown は長さに上限を付けて読み、インラインの正規表現の繰り返しにも上限を付ける（処理時間が入力の2乗に伸びない）。本文の画像は同じサイトのファイルだけ（外部の画像で閲覧者を追跡させない）。Webhook のアイコンの URL は https だけ。
- **アーカイブ・削除・無効化**：アーカイブしたチャンネルではリアクション・ピン留め・トピックの変更もできない。削除したメッセージの添付は取得できない。無効なユーザーは DM・チャンネルに加えられない。
  非公開チャンネルの送信の Webhook は、作った人が今もメンバーのときだけ送る。

## 5. 画面の自動同期（SSE と差分の取得）

- 画面は `GET /api/stream`（Server-Sent Events。参加していない公開チャンネルを見ているときは `?watch={id}`）につなぐ。
  サーバーは参加しているチャンネルに変更があれば `change`、自分への通知があれば `notify` を送る（20秒ごとに空の行で接続を保つ）。
- 知らせを受けると（まとめて1回）`GET /api/changes?since={eventId}` で変更を受け取り、表示中のチャンネル・スレッドは
  変わったメッセージだけを `POST /api/messages/batch` で取り直して差し替える。サイドバーの未読も取り直す。
- SSE がつながらないときは10秒ごと、つながっている間も念のため60秒ごとに確かめる。タブが非表示のあいだは止め、表示に戻ったときに確かめる。

## 6. メッセージ（リッチテキスト）

- エディタは Tiptap（skiwi・skea と共通の部品）。太字・斜体・取り消し線・コード・見出し・リスト・ToDo・引用・コードブロック（言語を選んでハイライト）・表・リンク・画像、
  `@` でメンション、`#` でチャンネル、`/` でブロックの挿入メニュー。チャットの入力欄では Enter で送信・Shift+Enter で改行（日本語の変換中・候補の一覧を開いているとき・コードブロックの中は除く）。
- 保存形式は ProseMirror の JSON。サーバーは JSON を検証し、許可したノード・マークだけから HTML を組み立てる（XSS 対策）。リンクの付いていない URL もリンクにする。
- 平文（検索用。添付のファイル名も含める）とメンションはサーバーで JSON から取り出す。API・MCP・Webhook からは Markdown（Slack の `<URL|文字>` も）で送れる。
- 入力中の内容は 1.5 秒の間を置いて下書きとして保存し、送ると消す。

## 7. 通知

- 宛先: メンションされた人（グループ・`@channel` はチャンネルのメンバー）、DM の相手、スレッドをフォローしている人（親の作者・返信した人・スレッドでメンションされた人）、
  「すべてのメッセージ」を選んだチャンネルのメンバー。操作した本人・ボットは除く。
- チャンネルごとの設定: 既定（DM はすべて・チャンネルはメンションだけ）・すべて・メンションだけ・通知しない。ミュートは直接のメンション以外を知らせない。
- メール: `mail_outbox` に `MAIL_DELAY_MINUTES`（既定10分）後に送るとして積み、送る時点で通知が既読なら送らない。同じチャンネルの通知はまとめて1通にする。
- デスクトップ通知: 画面が隠れているときに新しい通知が来たら、ブラウザの通知を出す（個人設定で止められる）。

## 8. リマインダー・定期メッセージ

- リマインダー: 時刻になると stui のボットとの DM に「⏰ リマインダー」を投稿し、通知する。メッセージについてのリマインダーはそのメッセージへのリンクを付ける。
  20秒ごとに全ノードで確かめ、SKIP LOCKED で1回だけ送る。
- 定期メッセージ: 作った人のタイムゾーンで次の送信日時を計算する（`Recurrence`）。送る時点で作った人の権限で投稿する（チャンネルを抜けていれば失敗として記録）。
  止まっていた間の分はまとめて1回だけ送る。1回だけのものは送ると止まる。

## 9. Webhook

- **受信**：`POST /hooks/{トークン}`。JSON（または `payload=JSON` のフォーム）で `text`・`username`・`icon_url`・`icon_emoji`・`attachments`
  （`pretext`・`title`・`title_link`・`text`・`fields`）を受け付け、Webhook ごとのボットとして投稿する。トークンはハッシュだけを保存し、作ったときだけ URL を表示する。
- **送信**：チャンネルごと（チャンネルの管理者）と全体（システム管理者。公開チャンネルだけ）。イベント（`message.created` など）を JSON で POST し、
  `X-Stui-Signature: sha256=<HMAC>` を付ける。トリガーの語で絞れる。応答に `{"text": …}` があればボットとして返信する（`response_type: comment` ならスレッドに）。
  ボットの投稿では送らない（往復を防ぐ）。`webhook_delivery` をアウトボックスとして再試行する。

## 10. 検索（日本語の曖昧検索）

- PGroonga のインデックスを `message.body_text` に作る。トークナイザ `TokenNgram`、ノーマライザ `NormalizerNFKC150`
  （`unify_kana`（ひらがな・カタカナ）、`unify_kana_case`、`unify_hyphen_and_prolonged_sound_mark`、`unify_middle_dot` など）。
- 語は空白区切りで AND、`-語` で除外、`"語句"`。完全な一致が5件より少なければ、タイプミスを許した一致（`fuzzy_max_distance_ratio = 0.34`）を下に足す。
- 絞り込み: `in:#チャンネル`・`in:@名前`（DM）・`from:@名前`・`from:me`・`with:@名前`（メンション）・`has:link|file|image|reaction|pin|meeting|thread`・
  `is:thread|reply|pinned|saved|bot|dm|unread`・`before:`・`after:`・`on:`（`2026-11-01`・`today`・`-7d`）・`during:2026-11`・`order:new|old|relevance`。
- 見られるチャンネル（公開・参加している非公開・DM）のものだけ。チャンネル・人・ファイル名の一致も合わせて出す。

## 11. MCP

- `POST /mcp`（Streamable HTTP、JSON 応答）。API キー（Bearer）だけを受け付ける。
- ツール: `get_me`・`list_channels`・`read_channel`・`read_thread`・`post_message`（スレッドへの返信・DM も）・`add_reaction`・`search_messages`・
  `list_threads`・`set_reminder`・`create_meeting`・`list_notifications`、そして画面でできるすべてのための `list_api_endpoints`・`call_api`。
  どのツールも内部で REST API を同じキーで呼ぶので、権限・スコープ・監査は REST API と同じになる。

## 12. 冗長構成・デプロイ

- skiwi・skea と同じく、各ノードに `stui-app`、Patroni + PostgreSQL(+PGroonga)、etcd。入口は keepalived（VIP）と HAProxy。
- DB の URL は複数ホスト + `targetServerType=primary`。`/healthz` は DB のプライマリに書けるときだけ 200。
- 起動時のマイグレーションは Flyway のロックで1回だけ適用される（複数ノード同時起動でも安全）。
- SSE はプロキシでバッファしないこと（`X-Accel-Buffering: no`。HAProxy は `timeout tunnel` を長めにする）。手順は [operations.md](operations.md)。

## 13. sbt モジュール構成

```
stui
├─ shared     バックエンドと画面(Scala.js)で共有する API の型（circe）
├─ core       ドメイン（検索の構文、定期メッセージの日時、リッチテキストの変換、チャンネル名・DM のキー）
├─ db         doobie のストアと Flyway マイグレーション、ジョブのリース、送信待ちの表の処理
├─ auth       OIDC クライアント、暗号（トークン、AES-GCM）
├─ storage    S3 互換ストレージ
├─ notify     メール（SMTP）
├─ api        http4s のルート（REST、SSE、MCP、Webhook、OAuth）とサービス（投稿・通知・Webhook・リマインダー・定期メッセージ）
├─ app        起動・設定（環境変数）・画面の配信
└─ web        Scalactive(Scala.js)で書いた画面
frontend/     Vite。web の出力と Tiptap をまとめてビルドし、app が配信する
```
