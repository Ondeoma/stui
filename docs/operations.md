# 構築・運用ガイド（VM 3台構成）

設定ファイルのひな形は [deploy/vm/](../deploy/vm/) にあります。IPアドレスは例です（`10.0.0.11〜13` が各VM、`10.0.0.10` がVIP）。
構成は兄弟プロジェクト [skiwi](https://github.com/Ondeoma/skiwi) と同じです。パッケージ名・パスは Debian/Ubuntu 系で書いています。

> 実機（VM・S3互換ストレージ・IdP）での構築は未検証です。同じ構成を Docker 上で再現した試験環境（`docker-compose.ha.yml`）があります。

## 1. 全体像

```
                  利用者 ──HTTPS──▶ VIP 10.0.0.10 (keepalived)
                                        │
   ┌────────────────────┬───────────────┴──────┬────────────────────┐
   │ stui1 10.0.0.11    │ stui2 10.0.0.12      │ stui3 10.0.0.13    │
   │  HAProxy :443      │  HAProxy             │  HAProxy           │  ← 3台とも同じ設定。VIPを持つ1台が受ける
   │  stui-app :8080    │  stui-app            │  stui-app          │  ← ステートレス。ラウンドロビンで振り分ける
   │  Patroni+PG17 :5432│  Patroni+PG17        │  Patroni+PG17      │  ← 1台がプライマリ、2台がレプリカ
   │  etcd :2379        │  etcd                │  etcd              │  ← Patroni のリーダー選出
   │  S3互換 :8000      │  S3互換              │  S3互換            │  ← 添付。外部のストレージでもよい
   └────────────────────┴──────────────────────┴────────────────────┘
```

| 役割 | 冗長性 | 1台停止時 |
|---|---|---|
| HAProxy + keepalived | VIP が生きている VM へ移る | 数秒で切替 |
| stui-app | 3台すべてで稼働。状態は DB と S3 にだけ持つ（セッションも DB） | HAProxy のヘルスチェックで外れる（約4秒）。画面の SSE はブラウザが自動でつなぎ直し、取りこぼしは差分の取得（`/api/changes`）で埋まる |
| バックグラウンド処理 | メール・送信の Webhook・リマインダー・定期メッセージは `FOR UPDATE SKIP LOCKED` で取り合う（二重に送らない）。掃除（古い通知・変更履歴・投稿されなかった添付）は `job_lock` のリースで1台だけが実行 | 残ったノードが引き継ぐ |
| PostgreSQL（Patroni） | 非同期レプリケーション、自動フェイルオーバー | プライマリ停止時は約30〜40秒書き込みが止まる。直前の数件のトランザクションが失われ得る（RPO 小） |
| etcd | 3台で合意（過半数） | 1台停止は影響なし |
| S3 互換ストレージ | ストレージ側で複製 | 読み書き継続 |

### 通信ポート（VM間）
| ポート | 用途 |
|---|---|
| 443/80 | 利用者 → HAProxy |
| 8080 | HAProxy → stui-app |
| 5432 | stui-app → PostgreSQL、レプリケーション |
| 8008 | Patroni REST API |
| 2379/2380 | etcd |
| VRRP（IP プロトコル 112） | keepalived |

外部への通信: IdP（OIDC）、SMTP、送信の Webhook の送信先、兄弟のサービス（skiwi・skawau・skea・skaka。連携する場合）。
受信: 受信の Webhook（`/hooks/{トークン}`）、兄弟のサービスからの通知（`/api/apps/notify`）。

> 送信の Webhook の送信先はチャンネルの管理者が決めます。アプリは内部のアドレス（ループバック・プライベート・リンクローカル）への送信を断りますが、
> 念のため、アプリのサーバーから内部のネットワーク（管理用の画面・クラウドのメタデータなど）へ出られないよう、ファイアウォールでも絞ってください。

## 2. 構築手順

### 2.1 共通
- 時刻同期（chrony）、ホスト名解決、ファイアウォール。Java 21。

### 2.2 etcd（3台）
[etcd.env](../deploy/vm/etcd.env) を各ノード用に書き換えて配置し、`systemctl enable --now etcd`。`etcdctl endpoint health --cluster` で確認。

### 2.3 PostgreSQL 17 + PGroonga + Patroni（3台）
1. PostgreSQL 17（PGDG）と PGroonga（`postgresql-17-pgdg-pgroonga`）を導入。OS 標準の `postgresql` サービスは無効化する。
2. Patroni（`pip install "patroni[etcd3]"`）。[patroni.yml](../deploy/vm/patroni.yml) をノードごとに書き換えて配置。
   `post_bootstrap` には [post_bootstrap.sh](../deploy/docker/post_bootstrap.sh) を使う（`STUI_DB_PASSWORD` を環境変数で渡す）。
3. 1台目から順に `systemctl enable --now patroni`。`patronictl -c /etc/patroni/patroni.yml list` で Leader 1台・Replica 2台を確認。
4. アプリの接続先は3台を並べた JDBC URL（`targetServerType=primary`）。

> **PGroonga とレプリケーションの注意**（skiwi の障害試験で判明）
> - `shared_preload_libraries = pgroonga_wal_resource_manager` と `pgroonga.enable_wal_resource_manager = on` を**全ノード**に設定する
>   （PGroonga の索引をレプリカへ複製するため。ないとフェイルオーバー後に検索・投稿がエラーになる）。
> - 強制終了した旧プライマリは `pg_rewind` に失敗するため、`remove_data_directory_on_rewind_failure: true` でデータを取り直して再参加させる。
> - `check_timeline: true` で、分岐したノードの昇格を防ぐ。

### 2.4 S3 互換ストレージ
パス形式でアクセスできる S3 互換ストレージを用意し、アプリ用のアクセスキーを発行する（バケット `stui`。なければ起動時に作る）。

### 2.5 stui-app（3台）
1. 開発機で `scripts/dist.sh` → `dist/`（`lib/*.jar` と `static/`）を各 VM の `/opt/stui/releases/<日付>/` に置き、`/opt/stui/current` をシンボリックリンクで向ける。
2. `useradd -r -s /usr/sbin/nologin stui`。[stui.env](../deploy/vm/stui.env) を `/etc/stui/stui.env`（0600）に、
   [stui.service](../deploy/vm/stui.service) を `/etc/systemd/system/` に置き、`NODE_ID` をノードごとに変える。
   `APP_SECRET` は3台共通の長いランダム値（`openssl rand -base64 32`）。**変えると保存済みの兄弟のサービスの OAuth トークンを読めなくなる（各ユーザーが連携し直す）。**
3. `systemctl enable --now stui`。初回起動時に1台が DB マイグレーションを行う（他は Flyway のロック待ち）。
4. 確認: `curl http://10.0.0.11:8080/healthz` → `ok stui1`。

### 2.6 HAProxy + keepalived（3台）
[haproxy.cfg](../deploy/vm/haproxy.cfg)・[keepalived.conf](../deploy/vm/keepalived.conf) を配置（`priority` をノードごとに変える）。
`PUBLIC_URL` は `https://stui.example.com`（Cookie に Secure が付く）。

### 2.7 IdP（OIDC）
- Redirect URI: `${PUBLIC_URL}/auth/callback`、Scopes: `openid profile email`、クライアント認証は `client_secret_post`。
- ID トークンの `groups` クレーム（`OIDC_GROUPS_CLAIM` で変更可）に所属グループを入れると、`ADMIN_GROUPS` による管理者の判定と、
  グループへのまとめてのメンション（`@開発` など）に使える。

## 3. バックアップ

| 対象 | 方法 | 頻度（例） |
|---|---|---|
| PostgreSQL | pgBackRest（[pgbackrest.conf](../deploy/vm/pgbackrest.conf)）。WAL アーカイブ＋フル／差分 | フル: 毎週、差分: 毎日、WAL: 常時 |
| 添付（`stui` バケット） | `rclone sync` で別の拠点へ複製 | 毎日 |
| 設定 | `/etc/stui`（APP_SECRET を含む）、`/etc/patroni`、`/etc/haproxy`、`/etc/keepalived` | 変更時 |

バックアップは**プライマリでのみ**実行する（`select pg_is_in_recovery()` が `f` のときだけ）。年1回程度は別環境への復旧を試す。

## 4. 日常運用

### アプリの更新（無停止）
1台ずつ: HAProxy で drain → `systemctl stop stui` → `/opt/stui/current` を切替 → `systemctl start stui` → `/healthz` を確認 → `state ready`。
DB マイグレーションを含む更新は、**後方互換なスキーマ変更**（列の追加など）に限る（旧版が同時に動くため）。

### DB の計画切替
`patronictl -c /etc/patroni/patroni.yml switchover --leader stui1 --candidate stui2`。

### 監視項目
| 対象 | 確認方法 |
|---|---|
| アプリ | `GET /healthz`（DB がプライマリで書ける状態なら 200）、HAProxy 統計 |
| DB | `patronictl list`（Leader 1・streaming 2、Lag）、ディスク使用量 |
| メール | `select count(*) from mail_outbox where sent_at is null and not skipped` |
| Webhook | `select count(*) from webhook_delivery where done_at is null and not failed` |
| リマインダー | `select count(*) from reminder where fired_at is null and remind_at < now() - interval '5 minutes'`（0 のはず） |
| 定期メッセージ | `select id, last_error from scheduled_message where last_error is not null` |

## 5. 試験環境（Docker）

```bash
scripts/ha-up.sh       # etcd + Patroni×3 + アプリ×3 + HAProxy + ScalaS3 + Mailpit（入口 http://localhost:8099、DEV_LOGIN）
scripts/ha-test.sh     # 障害試験: アプリ1台の停止、DB プライマリの停止の間も API が使えるか
docker compose -f docker-compose.ha.yml down
```
