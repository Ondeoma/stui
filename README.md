# stui

<p align="center"><img src="frontend/public/logo.svg" width="128" height="128" alt="stui のロゴ"></p>

stui は、チームのためのコミュニケーションツールです（Mattermost・Slack のようなチャット）。ログインは OpenID Connect（OIDC）。ライセンス: MIT

- **チャンネル**: 公開（誰でも参加できる）・非公開（招待した人だけ）。トピック・説明、メンバーと管理者、お気に入り、アーカイブ、
  全員を自動で参加させるチャンネル（最初は `#general`）、**ピン留め**、ヘッダーに並べる**ブックマーク**（リンク）、チャンネルを探す
- **スレッド**: どのメッセージにも返信できる（右の欄に開く）。「チャンネルにも送信」、フォロー（返信を通知）、フォロー中のスレッドの一覧と未読
- **DM**: 1対1・グループ（9人まで）・自分だけのメモ。一覧から隠せる（新しいメッセージで戻る）
- **メッセージ**: WYSIWYG（Tiptap。skiwi・skea と同じエディタ）。太字・リスト・ToDo・引用・言語を選べるコードブロック・表・リンク、
  `@` でメンション（人・IdP のグループ・`@channel`・`@here`）、`#` でチャンネル、`/` でメニュー、Enter で送信・Shift+Enter で改行、
  ファイル・画像の添付（貼り付け・ドロップ）、編集（↑ で直前の自分のメッセージ）・削除、リンクのコピー、未読に戻す、入力中の下書きの自動保存
- **絵文字のリアクション**（カスタム絵文字も登録できる）、**保存**（個人のブックマーク）、**リマインド**（20分後・明日の朝・日時を指定。stui から DM で知らせる）
- **定期メッセージ**: 1回だけ（送信の予約）・毎日・平日・毎週・毎月の決まった時刻に投稿する
- **skaka の会議をすぐに作る**: 入力欄のカメラのボタンで skaka（Web 会議）に会議室を作り、「参加する」ボタン付きの案内を投稿する
- **検索**: 日本語の全文検索（PGroonga）。かな・全角半角の揺れ、入力ミスを許す曖昧検索、
  `in:#開発 from:@山田 has:link is:thread after:-7d during:2026-11` のような絞り込み。チャンネル・人・ファイル名も
- **通知**: アクティビティ（メンション・DM・スレッド・リマインダー・連携サービス）、デスクトップ通知、メール（少し待って読まれなかったときだけ）。
  チャンネルごとの通知の設定（すべて・メンションだけ・通知しない）とミュート。未読の数はサイドバーとタブの題名に
- **Webhook**: 受信（Slack・Mattermost と同じ形の JSON でチャンネルに投稿）、送信（イベントを署名付きで POST。応答の text で返信。トリガーの語）
- **自動同期**: 変更はサーバーから画面へ即座に知らせる（Server-Sent Events。つながらないときは10秒ごとに確認）
- **兄弟のサービスとの連携**（OAuth）: skea・skiwi・skawau・skuaka・skaka からの通知を DM・チャンネルで受ける。
  メッセージに貼った skiwi のページ・skawau のファイル・skea のタスク・skorimako のトレースやアラートの URL に名前を出す（[docs/integrations.md](docs/integrations.md)）。
  skorimako（オブザーバビリティ）のアラートを、受信 Webhook でチャンネルに、OAuth で各自の DM に受ける
- **API・AI**: ユーザーごとのスコープ付き API キー、REST API、MCP（AI から画面と同じ操作ができる）。
  外部の AI エージェント（skakapo）用の、管理画面で作るエージェント専用アカウント
- **認証・権限**: OIDC、システム管理者・一般、チャンネルのメンバー・管理者
- **冗長化**: アプリはステートレスで複数ノードで動く。PostgreSQL は Patroni、添付は S3 互換ストレージ
- レスポンシブ対応（スマホの幅ではサイドバーを引き出す）、ダークモード

設計は [docs/design.md](docs/design.md)、構築・運用は [docs/operations.md](docs/operations.md)、兄弟のサービスとの連携は [docs/integrations.md](docs/integrations.md) を参照してください。

兄弟のシステム: [skea](https://github.com/Ondeoma/skea)（タスク管理）、[skiwi](https://github.com/Ondeoma/skiwi)（Wiki）、
[skawau](https://github.com/Ondeoma/skawau)（ファイル共有）、[skuaka](https://github.com/Ondeoma/skuaka)（カレンダー）、[skaka](https://github.com/Ondeoma/skaka)（Web 会議）。

## 構成

| モジュール | 内容 |
|---|---|
| `modules/shared` | バックエンドと画面（Scala.js）で共有する API の型（circe） |
| `modules/core` | ドメインの純粋なロジック（検索の絞り込み構文・定期メッセージの日時・リッチテキストの変換） |
| `modules/db` | doobie のストア、Flyway のマイグレーション、ジョブのリース、送信待ちの表の処理 |
| `modules/auth` | OIDC（Authorization Code + PKCE）、APIキー、暗号化 |
| `modules/storage` | S3 互換ストレージ（添付ファイル） |
| `modules/notify` | メール（SMTP） |
| `modules/api` | http4s のルート（REST・SSE・MCP・Webhook・OAuth）と、投稿・通知・Webhook・リマインダー・定期メッセージのサービス |
| `modules/app` | 起動・設定（環境変数）・画面の配信 |
| `modules/web` | 画面（[Scalactive](https://github.com/Ondeoma/scalactive) の `.st` 単一ファイルコンポーネント） |
| `frontend/` | Vite。Scala.js の出力と Tiptap（エディタ）をまとめてビルドする |
| `deploy/` | Docker（試験用の3ノード構成）と VM 構築用の設定のひな形 |

技術: Scala 3.9 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway、PostgreSQL 17 + PGroonga、Scala.js + Scalactive、Tiptap。

## 開発

必要なもの: JDK 25、sbt、Node.js、Docker。

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # PostgreSQL+PGroonga (:54379)、ScalaS3 (:3920)、Mailpit (SMTP :1027、画面 :8027)
scripts/dev-restart.sh                                   # バックエンドを :8098 で起動（scripts/dev-env.sh の設定。DEV_LOGIN=true）
(cd frontend && npm install && npm run dev)              # 画面の開発サーバー http://localhost:5178（/api などは :8098 へ中継）
sbt ~web/fastLinkJS                                      # 画面の変更を再リンクし続ける（ブラウザを再読み込みすると反映）
```

- 開発用のログイン（`DEV_LOGIN=true`）では、名前を入れるだけでログインできます。本番では使わないでください。
- `scripts/seed-demo.sh` でデモのチャンネル・メッセージを作れます。`scripts/smoke.sh` で API を一通り確認できます。
- 送信されたメールは Mailpit（http://localhost:8027）で見られます。

テスト:

```bash
sbt test                                                                       # DB を使うテストは TEST_DATABASE_URL がなければ飛ばす
TEST_DATABASE_URL=jdbc:postgresql://localhost:54379/stui_test sbt db/test        # 開発用の DB の stui_test を使う
```

**DB マイグレーション**（`modules/db/src/main/resources/db/migration`）: スキーマを変えるときは新しい番号の `V<n>__*.sql` を追加し、適用済みのファイルは書き換えないでください。

## 配布・起動

```bash
scripts/dist.sh                   # dist/lib/*.jar と dist/static（画面）を作る
java -cp 'dist/lib/*' stui.app.Main
```

主な環境変数（すべては `modules/app/src/main/scala/stui/app/Main.scala` の `Config`）:

| 変数 | 内容 |
|---|---|
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | PostgreSQL（PGroonga が必要）。冗長構成では3台を並べて `targetServerType=primary` |
| `PUBLIC_URL` | 公開 URL（`https://` なら Cookie に Secure が付く） |
| `OIDC_ISSUER` / `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | OIDC。Redirect URI は `${PUBLIC_URL}/auth/callback`。ログアウトでは IdP からもログアウトし、`${PUBLIC_URL}/` に戻る（sruru 以外の IdP では、この戻り先も登録する） |
| `OIDC_GROUPS_CLAIM` | 所属グループのクレーム名（既定 `groups`） |
| `ADMIN_GROUPS` | システム管理者にするグループ（カンマ区切り）。画面からも管理者を付けられる |
| `APP_SECRET` | 秘密の値の暗号化（兄弟のサービスの OAuth トークン）に使う16文字以上の値 |
| `STATIC_DIR` | ビルド済みの画面（`dist/static`） |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 添付ファイル（未設定なら添付は使えない） |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_TLS` / `SMTP_USER` / `SMTP_PASSWORD` / `MAIL_FROM` | メール（未設定ならメールは送らない） |
| `TIMEZONE` | 新しいユーザーの既定のタイムゾーン（既定 `Asia/Tokyo`。各ユーザーは個人設定で変えられる。検索の日付・定期メッセージの時刻に使う） |
| `MAIL_DELAY_MINUTES` | 通知からメールを送るまでの分（既定 10。その間に画面で読まれたら送らない） |
| `MAX_UPLOAD_MB` | 添付の上限（既定 100） |
| `WEBHOOK_ALLOW_HTTP` | 送信の Webhook の送信先に http:// を許す（開発用） |
| `WEBHOOK_ALLOW_PRIVATE` | 送信の Webhook の送信先に内部のネットワーク（localhost・プライベートな IP）を許す（開発用。既定では SSRF を防ぐため断る） |
| `RATE_LIMIT_AUTH` / `RATE_LIMIT_PUBLIC` | IP ごとの1分あたりの回数の上限。ログインと OAuth の認可の画面（`/auth/…`・`/oauth/authorize`、既定 20）と、OAuth のトークンの発行・失効（`/oauth/token`・`/oauth/revoke`、既定 120）。0 で制限しない。ノードごとに数える。受信の Webhook（`/hooks/…`）と、ログインした人・APIキー（Bot を含む）の API は数えない |
| `TRUSTED_PROXIES` | リバースプロキシの IP か CIDR（カンマ区切り）。そこから来た要求だけ `X-Forwarded-For` から利用者の IP を読む。リバースプロキシの後ろに置くときは必ず設定する（未設定だと全員がプロキシの IP として数えられ、上限にすぐ達する） |
| `OAUTH_CLIENTS` | stui を使う兄弟のサービス。`[{"id":"skea","secret":"…（16文字以上）","redirectUri":"https://…/callback","name":"skea","scopes":["read","write"]}]` |
| `SKIWI_*`・`SKAWAU_*`・`SKEA_*`・`SKAKA_*`（`_URL`・`_CLIENT_ID`・`_CLIENT_SECRET`・`_INTERNAL_URL`） | stui から使う兄弟のサービス（リンクの名前・会議室）。[docs/integrations.md](docs/integrations.md) |
| `NODE_ID` | ノード名（ログ・定期処理のリース） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_HEADERS` | ログ・メトリクス・トレースの送り先（skorimako の URL と `Authorization=Bearer sko_…`（ingest のキー））。未設定なら送らない。そのほか OpenTelemetry の標準の `OTEL_*` が使える |
| `LOG_FORMAT` / `LOG_LEVEL` | 標準出力のログの形（`text`（既定）・`json`）と水準（既定 `INFO`）。ログにはトレースの ID と操作したユーザーが付く |
| `SKORIMAKO_RUM_TOKEN` / `SKORIMAKO_RUM_URL` / `SKORIMAKO_RUM_SAMPLE_RATE` | 画面のブラウザの観測（skorimako の RUM）。トークンは skorimako の管理の画面で登録したアプリのもの。URL を省略すると `SKORIMAKO_URL` |
| `VAPID_SUBJECT` | プッシュ通知（PWA）の連絡先（`mailto:…` か `https://…`。省略すると `PUBLIC_URL`）。VAPID の鍵は初回の起動で作って DB に保存する |
| `DEV_LOGIN` | 開発用のログインを有効にする（開発専用。OIDC と同時には指定できず、https の `PUBLIC_URL` では起動しない） |

## Mattermost からの取り込み

Mattermost の DB のダンプ（PostgreSQL）から、ユーザー・チャンネル・投稿などを取り込めます。サーバーとは別のコマンドで動かします。

```bash
# 1. Mattermost の DB をダンプする（Mattermost 側で）
pg_dump -Fc -U mmuser mattermost > mattermost.dump

# 2. 一時的な PostgreSQL に戻す（取り込みはこの DB を読むだけ）
docker run -d --name mm-import -e POSTGRES_USER=mmuser -e POSTGRES_PASSWORD=… -e POSTGRES_DB=mattermost -p 127.0.0.1:55432:5432 postgres:16
docker exec -i mm-import pg_restore -U mmuser -d mattermost --no-owner < mattermost.dump

# 3. 取り込む（取り込み先は stui と同じ DATABASE_*・S3_*。先に --dry-run で数を確かめられる）
export MATTERMOST_DATABASE_URL=jdbc:postgresql://localhost:55432/mattermost MATTERMOST_DATABASE_USER=mmuser MATTERMOST_DATABASE_PASSWORD=…
java -cp 'dist/lib/*' stui.app.ImportMattermost --dry-run
java -cp 'dist/lib/*' stui.app.ImportMattermost --files /opt/mattermost/data
```

| 引数 | 内容 |
|---|---|
| `--team <名前>` | 取り込むチーム（Mattermost の URL に出るチームの名前）。複数指定できる。省くとすべてのチーム |
| `--files <ディレクトリ>` | Mattermost のファイルの置き場所（`config.json` の `FileSettings.Directory`。サーバーから写したものでよい）。添付ファイルとカスタム絵文字を取り込む（S3 の設定が必要） |
| `--link-by-email` | メールアドレスが未確認の既存のユーザーにも、同じメールアドレスで結び付ける。既定では、IdP がメールアドレスを確かめ済み（`email_verified`）としてログインした人にだけ結び付ける（確かめ済みかどうかは次のログインで記録される） |
| `--dry-run` | 書き込まずに、取り込む数だけを表示する |

- **取り込むもの**: ユーザー、チャンネル（公開・非公開・DM・グループ DM。アーカイブも）とメンバー、投稿（スレッド・ピン留め・編集済み・Webhook の投稿と attachments）、
  `@ユーザー名`・`@channel`・`@here` のメンション、リアクション、添付ファイル、カスタム絵文字。投稿の時刻・投稿者は Mattermost のまま。各メンバーの既読の位置も、Mattermost で最後に見た時刻に合わせる。
- **取り込まないもの**: 消した投稿・編集前の版、システムの投稿（参加・退出・ヘッダーの変更など）、通知の設定、お気に入り、Mattermost のプラグインのデータ。
- **ユーザー**: 同じメールアドレスの人が stui にいればその人に結び付け、いなければ作る（sub は `mattermost:<ID>`）。作ったユーザーは、**同じメールアドレスで最初に OIDC でログインしたとき**に、その人のアカウントとして引き継がれる。Mattermost で無効にした人は無効のまま。
- **チャンネル**: stui にはチームがないので、すべてのチームのチャンネルを同じ一覧に並べる。名前がぶつかったら「名前 (チーム名)」にする。同じ顔ぶれの DM がすでにあれば、そこに足す。
- **やり直し**: 取り込んだものは `import_map` 表に記録するので、もう一度実行しても二重に作らない（Mattermost を使い続けている間に何度か流し、最後に差分だけを足す、という移行もできる）。
- **リアクション**: Mattermost の絵文字の名前（`thumbsup` など）は stui の絵文字にする。stui の一覧にないものは `:名前:`（カスタム絵文字）として残す。
- **注意**: 並び順の通し番号は取り込んだ順に振るので、使い始めた stui に取り込むと、既存の DM に足した古い投稿が新しい投稿のあとに並ぶ。できるだけ使い始める前に取り込む。取り込み元は PostgreSQL の Mattermost だけ（MySQL のものは、Mattermost の手順で PostgreSQL に移してから）。

## API・MCP・Webhook

- 画面の「個人設定」で APIキー（`stui_` で始まる）を発行し、`Authorization: Bearer <キー>` で送ります。
  スコープは `read`（参照）・`write`（投稿・更新・削除）・`admin`（管理。管理者のみ）です。
- REST API の一覧: `GET /api/endpoints`。
- MCP: `POST /mcp`（Streamable HTTP）。例（Claude Code）:

```bash
claude mcp add --transport http stui https://stui.example.com/mcp --header "Authorization: Bearer stui_xxxxxxxx"
```

MCP のツール: `list_channels`・`read_channel`・`read_thread`・`post_message`・`add_reaction`・`search_messages`・`list_threads`・
`set_reminder`・`create_meeting`・`list_notifications`・`get_me`、および画面でできるすべての操作のための `list_api_endpoints`・`call_api`。

### エージェント専用アカウント

skakapo などの外部の AI エージェントが MCP・REST API を使うための、ログインしないアカウントです（人の APIキーを借りずに済む）。
システム管理者が「管理」→「エージェント」で作り、APIキーを発行します。

- 中身はボット（`is_bot`）の一種で、sub は `bot:agent:<UUID>`、メールはありません。OIDC・開発用のログインではこの sub にならず
  （OIDC は `bot:` で始まる sub を断る）、同じメールの IdP のユーザーに乗っ取られることもありません。
- エージェントは**このアカウント自身の権限**で動き、投稿・変更はこのアカウントが行ったものとして記録されます。
  公開チャンネルにはエージェントが自分で参加できます（`POST /api/channels/{id}/join`）。非公開チャンネルは管理画面の「チャンネルに参加させる」で参加させます。
  ボットなので通知（メール・プッシュ）は届かず、メンションの候補にも出ません。
- キーのスコープは `read`・`write` だけです（`admin` は付けられません）。無効にしたアカウントのキー・取り消したキーは使えなくなります。
- 管理の API（`/api/admin/agents…`）は管理者の画面（Cookie のセッション）からだけ使えます。APIキーでは admin スコープがあっても使えません。
  操作は監査ログ（`agent.create`・`agent.update`・`agent.api_key.create`・`agent.api_key.revoke`・`agent.channel.join`・`agent.channel.leave`）に残ります。

設定の手順:

1. 「管理」→「エージェント」でアカウントを作る（名前の例: skakapo）。
2. 「チャンネルに参加させる」で、エージェントに使わせる非公開チャンネルを選ぶ（公開チャンネルはエージェントが自分で参加できる）。
3. 「APIキーを発行」でスコープ（読むだけなら `read`、投稿もさせるなら `read`・`write`）と有効期限を選んで発行し、表示されたキーをコピーする（二度と表示されない）。
4. skakapo の管理画面の「サービス」→ stui →「エージェント専用アカウント」に貼り付ける。

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/admin/agents` | 一覧（APIキー（取り消したものも）と参加しているチャンネルつき。キーのハッシュは返さない） |
| POST | `/api/admin/agents` | 作る `{name}`（1〜60 文字） |
| PATCH | `/api/admin/agents/{id}` | 名前の変更・無効化 `{name?, disabled?}` |
| POST | `/api/admin/agents/{id}/keys` | APIキーを発行 `{name, scopes, expiresInDays?}`（平文のキーは応答で1回だけ返す） |
| DELETE | `/api/admin/agents/{id}/keys/{keyId}` | APIキーを取り消す |
| POST | `/api/admin/agents/{id}/channels` | チャンネルに参加させる `{channelIds}` |
| DELETE | `/api/admin/agents/{id}/channels/{channelId}` | チャンネルから外す |

受信の Webhook（チャンネルの ⚙ →「連携・定期メッセージ」で作る）:

```bash
curl -X POST -H 'Content-Type: application/json' -d '{"text": "デプロイが完了しました <https://ci.example.com/1|#1>"}' https://stui.example.com/hooks/xxxxxxxx
```
