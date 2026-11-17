# 兄弟のサービスとの連携

stui は兄弟のサービス（[skea](https://github.com/Ondeoma/skea)（タスク管理）・[skiwi](https://github.com/Ondeoma/skiwi)（Wiki）・
[skawau](https://github.com/Ondeoma/skawau)（ファイル共有）・[skuaka](https://github.com/Ondeoma/skuaka)（カレンダー）・
[skaka](https://github.com/Ondeoma/skaka)（Web 会議））と、OAuth 2.0（認可コード + PKCE）でつながります。向きは2つあります。

| 向き | 使いみち | stui の役割 |
|---|---|---|
| 兄弟 → stui | 各種の通知（タスクの担当・Wiki の更新・予定のリマインドなど）を stui の DM やチャンネルに送る。stui のメッセージのリンクに概要を出す | 認可サーバー（`/oauth/*`） |
| stui → 兄弟 | メッセージに貼った skiwi のページ・skawau のファイル・skea のタスクの URL に名前を出す。skaka に会議室をすぐ作る | クライアント（`/api/integrations/{サービス}/*`） |

どちらも、トークンは「そのユーザー自身の権限」で発行されます。見られないものは見えません。

## 1. 兄弟 → stui（通知を送る）

### stui 側の設定（`OAUTH_CLIENTS`）

```json
[{"id": "skea", "secret": "（16文字以上）", "redirectUri": "https://skea.example.com/api/integrations/stui/callback",
  "name": "skea", "scopes": ["read", "write"]}]
```

- `scopes`: `read`（メッセージの概要を読む）・`write`（通知を送る）。省略時は `read` だけ。
- `redirectUri` は完全一致で照合します（開発時のみ `http://localhost…` を許可）。

### 認可（skiwi・skea と同じ形）

- `GET /oauth/authorize`: `response_type=code`、`client_id`、`redirect_uri`、`scope=read write`、`state`、`code_challenge`（S256・43文字）、
  `code_challenge_method=S256`。ログインしていなければ stui のログインを経て戻り、許可の画面を出します。
  許可: `302 {redirect_uri}?code=…&state=…`、拒否: `?error=access_denied&state=…`。
- `POST /oauth/token`（クライアント認証は HTTP Basic、`application/x-www-form-urlencoded`）:
  `grant_type=authorization_code`、`code`、`redirect_uri`、`code_verifier`（43〜128文字）。
  応答: `{"access_token": "stui_…", "token_type": "Bearer", "scope": "read write"}`。コードは60秒・1回だけ。許可し直すと前のトークンは失効します。
- `POST /oauth/revoke`（Basic）: `token=stui_…`。いつも 200。
- 発行したトークンは、そのユーザーの API キー（名前「<サービス名> 連携」）です。ユーザーは個人設定の「APIキー」から失効できます。

### 通知を送る: `POST /api/apps/notify`

`Authorization: Bearer stui_…`（`write` のトークン）。

```json
{ "title": "STUI-12 の担当になりました", "text": "ログイン画面の修正（期日 11/30）", "url": "https://skea.example.com/tasks/STUI-12" }
```

- `channel` がなければ、ボット（サービスの名前）からトークンの持ち主への **DM** として投稿し、画面内の通知（種類「連携サービス」）も出します。
- `channel`（チャンネルの ID か名前）を付けると、トークンの持ち主が参加しているチャンネルにボットとして投稿します（持ち主の投稿の権限が必要）。
- `text` は Markdown（太字・リンク・箇条書き・コード）。`title` と `url` があれば、見出しをリンクにします。
- 応答は投稿したメッセージ（201）。401 はトークンの失効です（連携し直しを促してください）。

### メッセージ・チャンネルの概要を読む

- `POST /api/messages/lookup` `{"ids": ["…"]}`（100件まで、`read` で可）→
  `{"messages": [{"id", "channel": {"id", "name", "kind"}, "author", "text", "url"}]}`。見られないものは返しません。
- `POST /api/channels/lookup` `{"ids": ["…"]}` → `{"channels": [{"id", "name", "kind", "topic"}]}`。
- メッセージの URL は `{stui の URL}/m/{メッセージID}`、チャンネルは `{stui の URL}/c/{チャンネルID}` です。

## 2. stui → 兄弟（リンクの名前・会議室）

### stui 側の設定

| 環境変数 | 内容 |
|---|---|
| `SKIWI_URL`・`SKIWI_CLIENT_ID`・`SKIWI_CLIENT_SECRET` | skiwi のページのタイトル |
| `SKAWAU_URL`・`SKAWAU_CLIENT_ID`・`SKAWAU_CLIENT_SECRET` | skawau のファイル・フォルダの名前 |
| `SKEA_URL`・`SKEA_CLIENT_ID`・`SKEA_CLIENT_SECRET` | skea のタスクのタイトルと状態 |
| `SKAKA_URL`・`SKAKA_CLIENT_ID`・`SKAKA_CLIENT_SECRET` | skaka の会議室の作成 |
| `*_INTERNAL_URL` | サーバーから呼ぶ URL（省略時は `*_URL`。コンテナの中から呼ぶときなど） |

相手のサービスの `OAUTH_CLIENTS` に stui を登録します。`redirectUri` は `{stui の URL}/api/integrations/{サービス}/callback`。
skaka だけはスコープ `["read", "write"]`（会議室を作るため）、ほかは `read` です。

### 各ユーザーの連携

個人設定の「{サービス} 連携」で「連携する」を押し、相手の許可画面で許可します。トークンは `APP_SECRET` から作る鍵で AES-GCM 暗号化して保存します。

- `GET /api/integrations/links`: 設定されたサービスと、自分が連携済みか
- `GET /api/integrations/{サービス}/connect` → 相手の `/oauth/authorize` へ（state と PKCE の verifier は DB に10分）
- `GET /api/integrations/{サービス}/callback`: コードをトークンに替えて保存し、個人設定へ戻る
- `DELETE /api/integrations/{サービス}/connection`: 相手の `/oauth/revoke` を呼んでから消す

### リンクの名前（skiwi・skawau・skea）

画面（`frontend/src/links.js`）がメッセージの中のリンクを探し、`POST /api/integrations/{サービス}/titles` `{"ids": [...]}` で問い合わせます。
stui のサーバーは本人のトークンで相手の API を呼び、60秒キャッシュします。

| サービス | URL の形 | 相手の API |
|---|---|---|
| skiwi | `/w/{ワークスペース}/p/{ページID}` | `POST /api/pages/lookup {ids}` → `{pages: [{id, title, icon}]}` |
| skawau | `/view/{ID}`・`/office/{ID}`・`/files/{ID}` | `POST /api/files/lookup {ids}` → `{files: [{id, name, folder}]}` |
| skea | `/tasks/{タスクID}`・`/p/{キー}/tasks/{タスクID}` | `POST /api/tasks/lookup {keys}` → `{tasks: [{key, title, status: {name, category}}]}` |

リンクの文字が URL のままなら名前に置き換え、文字を書いたリンクは名前を横に添えます。編集中のエディタの中は書き換えません。

### 会議室（skaka）

チャンネル・スレッドの入力欄のカメラのボタン（MCP では `create_meeting`）で `POST /api/channels/{id}/meeting` `{name?, rootId?}` を呼ぶと、
stui は本人のトークンで skaka の `POST /api/rooms` `{"name", "description", "guestAccess": false, "lobby": false}` を呼び、
返ってきた会議室の URL を `props.meeting = {url, name, code}` 付きのメッセージとして投稿します（画面では「参加する」ボタンの付いたカード）。
連携していなければ 409 を返し、画面は連携の画面へ案内します。
