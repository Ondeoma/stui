-- stui のスキーマ
-- 適用済みのマイグレーションは書き換えないこと。変更は新しい番号の V<n>__*.sql を追加する。

create extension if not exists pgroonga;

-- ------------------------------------------------------------------ ユーザー・認証

create table app_user (
  id            uuid primary key,
  sub           text not null unique,            -- OIDC の sub（ボットは bot:<名前>）
  name          text not null,
  email         text,
  avatar_url    text,
  avatar_key    text,                            -- 画面から登録したアイコン（S3 のキー）。登録している間は IdP のアイコンで上書きしない
  is_admin      boolean not null default false,  -- 画面で付けた管理者権限（ADMIN_GROUPS の所属者は別途管理者）
  is_bot        boolean not null default false,  -- Webhook・連携サービス・リマインダーの発言者
  disabled      boolean not null default false,
  timezone      text not null default 'Asia/Tokyo',
  created_at    timestamptz not null default now(),
  last_login_at timestamptz
);
create index app_user_name_idx on app_user using pgroonga (name);

create table user_group (
  id   uuid primary key,
  name text not null unique
);

create table group_member (
  group_id uuid not null references user_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);

create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index session_expires_idx on session (expires_at);

-- OIDC のログイン途中の状態（複数ノードのどこに戻ってきてもよいよう DB に置く）
create table login_state (
  state      text primary key,
  verifier   text not null,
  nonce      text not null,
  return_to  text not null default '/',
  created_at timestamptz not null default now()
);

create table api_key (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  prefix       text not null,                    -- 画面に出す先頭部分（stui_xxxx）
  key_hash     bytea not null unique,
  scopes       text[] not null,                  -- read / write / admin
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz,
  oauth_client text                              -- 外部サービスへの認可で発行したキーのクライアント（許可し直したら前のキーを失効させる）
);
create index api_key_user_idx on api_key (user_id);
create index api_key_oauth_idx on api_key (user_id, oauth_client) where oauth_client is not null;

-- ------------------------------------------------------------------ チャンネル

-- kind: public（誰でも参加できる）/ private（招待された人だけ）/ dm（1対1・自分だけ）/ group（3人以上の DM）
create table channel (
  id              uuid primary key,
  name            text not null,                 -- public・private は表示名（重複不可）。dm・group は dm_key と同じ
  kind            text not null check (kind in ('public', 'private', 'dm', 'group')),
  topic           text not null default '',
  purpose         text not null default '',
  dm_key          text unique,                   -- dm・group: 参加者の ID を並べたもの（同じ顔ぶれの DM は1つ）
  archived        boolean not null default false,
  auto_join       boolean not null default false, -- 新しいユーザーを自動で参加させる（#general など）
  created_by      uuid references app_user(id) on delete set null,
  created_at      timestamptz not null default now(),
  last_message_at timestamptz
);
create unique index channel_name_idx on channel (lower(name)) where kind in ('public', 'private');
create index channel_search_idx on channel using pgroonga ((array[name, topic, purpose]) pgroonga_text_array_full_text_search_ops_v2);

create table channel_member (
  channel_id    uuid not null references channel(id) on delete cascade,
  user_id       uuid not null references app_user(id) on delete cascade,
  role          text not null default 'member' check (role in ('member', 'admin')),
  notify        text not null default 'default' check (notify in ('default', 'all', 'mention', 'none')),
  muted         boolean not null default false,
  favorite      boolean not null default false,
  hidden        boolean not null default false,  -- DM を一覧から隠す（新しいメッセージが来たら戻す）
  last_read_seq bigint not null default 0,
  joined_at     timestamptz not null default now(),
  primary key (channel_id, user_id)
);
create index channel_member_user_idx on channel_member (user_id);

-- チャンネルのブックマーク（ヘッダーに並べるリンク）
create table channel_bookmark (
  id         uuid primary key,
  channel_id uuid not null references channel(id) on delete cascade,
  title      text not null,
  url        text not null,
  emoji      text not null default '',
  position   int not null default 0,
  created_by uuid references app_user(id) on delete set null,
  created_at timestamptz not null default now()
);
create index channel_bookmark_channel_idx on channel_bookmark (channel_id, position);

-- ------------------------------------------------------------------ メッセージ

create table message (
  id            uuid primary key,
  seq           bigserial not null unique,       -- 全体の通し番号（未読の判定・並び順）
  channel_id    uuid not null references channel(id) on delete cascade,
  root_id       uuid references message(id) on delete cascade,  -- スレッドの返信なら親メッセージ
  also_channel  boolean not null default false,  -- スレッドの返信をチャンネルにも表示する
  author_id     uuid references app_user(id) on delete set null,
  kind          text not null default 'user' check (kind in ('user', 'system', 'bot')),
  via           text not null default 'web',     -- web / api / mcp / webhook / app / reminder / schedule
  body          jsonb not null,                  -- Tiptap(ProseMirror) の JSON
  body_text     text not null default '',        -- 検索用の平文（添付ファイル名も含める）
  props         jsonb not null default '{}',     -- 会議・Webhook の表示名・連携サービスなどの付加情報
  reply_count   int not null default 0,
  last_reply_at timestamptz,
  pinned_at     timestamptz,
  pinned_by     uuid references app_user(id) on delete set null,
  created_at    timestamptz not null default now(),
  edited_at     timestamptz,
  deleted_at    timestamptz
);
create index message_channel_idx on message (channel_id, seq) where root_id is null;
create index message_channel_all_idx on message (channel_id, seq);
create index message_root_idx on message (root_id, seq) where root_id is not null;
create index message_author_idx on message (author_id, seq);
create index message_pinned_idx on message (channel_id, pinned_at) where pinned_at is not null;
create index message_search_idx on message using pgroonga (body_text)
  with (
    tokenizer = 'TokenNgram("unify_alphabet", false, "unify_symbol", false, "unify_digit", false, "loose_symbol", true)',
    normalizers = 'NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true, "unify_katakana_v_sounds", true)'
  );

create table reaction (
  message_id uuid not null references message(id) on delete cascade,
  user_id    uuid not null references app_user(id) on delete cascade,
  emoji      text not null,
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, emoji)
);

-- スレッドのフォロー（返信の通知と、スレッドの一覧の未読）
create table thread_follow (
  root_id       uuid not null references message(id) on delete cascade,
  user_id       uuid not null references app_user(id) on delete cascade,
  following     boolean not null default true,
  last_read_seq bigint not null default 0,
  primary key (root_id, user_id)
);
create index thread_follow_user_idx on thread_follow (user_id) where following;

-- 保存したメッセージ（個人のブックマーク）
create table saved_message (
  user_id    uuid not null references app_user(id) on delete cascade,
  message_id uuid not null references message(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, message_id)
);

create table attachment (
  id           uuid primary key,
  channel_id   uuid not null references channel(id) on delete cascade,
  message_id   uuid references message(id) on delete cascade, -- 投稿前は null（投稿したら結び付ける）
  s3_key       text not null,
  file_name    text not null,
  size         bigint not null,
  content_type text not null,
  sha256       text not null,
  inline       boolean not null default false,   -- 本文に埋め込んだ画像（添付の一覧には出さない）
  created_by   uuid references app_user(id),
  created_at   timestamptz not null default now()
);
create index attachment_message_idx on attachment (message_id);
create index attachment_channel_idx on attachment (channel_id, created_at);

-- 入力中のメッセージの下書き（ユーザー＋チャンネル＋スレッドごとに1つ）
create table message_draft (
  user_id    uuid not null references app_user(id) on delete cascade,
  channel_id uuid not null references channel(id) on delete cascade,
  root_id    uuid references message(id) on delete cascade,
  body       jsonb not null,
  updated_at timestamptz not null default now()
);
create unique index message_draft_idx on message_draft (user_id, channel_id, coalesce(root_id, '00000000-0000-0000-0000-000000000000'));

-- リアクションに使えるカスタム絵文字（システム全体。:name: で使う）
create table custom_emoji (
  id           uuid primary key,
  name         text not null unique check (name ~ '^[a-z0-9_+-]{2,32}$'),
  s3_key       text not null,
  content_type text not null,
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now()
);

-- 変更履歴（画面の自動同期に使う。id の大きいものが新しい）
create table chat_event (
  id         bigserial primary key,
  channel_id uuid not null references channel(id) on delete cascade,
  kind       text not null,                      -- message.created / message.updated / message.deleted / channel.updated / member.joined …
  message_id uuid,
  root_id    uuid,
  actor_id   uuid,
  user_id    uuid,                               -- メンバーの変更の対象
  at         timestamptz not null default now()
);
create index chat_event_channel_idx on chat_event (channel_id, id);

-- ------------------------------------------------------------------ リマインダー・定期メッセージ

create table reminder (
  id         uuid primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  text       text not null default '',
  message_id uuid references message(id) on delete cascade,
  remind_at  timestamptz not null,
  fired_at   timestamptz,
  done_at    timestamptz,
  created_at timestamptz not null default now()
);
create index reminder_due_idx on reminder (remind_at) where fired_at is null;
create index reminder_user_idx on reminder (user_id, remind_at);

-- frequency: once（1回だけ）/ daily / weekdays（平日）/ weekly / monthly
create table scheduled_message (
  id          uuid primary key,
  channel_id  uuid not null references channel(id) on delete cascade,
  author_id   uuid not null references app_user(id) on delete cascade,
  root_id     uuid references message(id) on delete cascade,
  body        jsonb not null,
  frequency   text not null check (frequency in ('once', 'daily', 'weekdays', 'weekly', 'monthly')),
  run_date    date,                              -- once の日付
  run_time    text not null,                     -- HH:mm（timezone の時刻）
  weekday     int check (weekday between 1 and 7),
  monthday    int check (monthday between 1 and 31),
  timezone    text not null default 'Asia/Tokyo',
  next_run    timestamptz,
  enabled     boolean not null default true,
  last_run_at timestamptz,
  last_error  text,
  created_at  timestamptz not null default now()
);
create index scheduled_message_next_idx on scheduled_message (next_run) where enabled;

-- ------------------------------------------------------------------ 通知

create table notification (
  id         uuid primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  kind       text not null,                        -- mention / dm / thread / reminder / app
  channel_id uuid references channel(id) on delete cascade,
  message_id uuid references message(id) on delete cascade,
  actor_id   uuid references app_user(id) on delete set null,
  title      text not null,
  body       text not null default '',
  at         timestamptz not null default now(),
  read_at    timestamptz
);
create index notification_user_idx on notification (user_id, at desc);
create index notification_unread_idx on notification (user_id) where read_at is null;
create index notification_message_idx on notification (message_id);

-- 通知の種類ごとのメール送信の可否（行がなければ送る）
create table notify_pref (
  user_id uuid not null references app_user(id) on delete cascade,
  kind    text not null,
  email   boolean not null,
  primary key (user_id, kind)
);

create table mail_outbox (
  id              uuid primary key,
  user_id         uuid references app_user(id) on delete cascade,
  to_address      text,
  notification_id uuid,                            -- 送信時に既読ならメールは送らない
  subject         text not null,
  body            text not null,
  dedupe_key      text,
  not_before      timestamptz not null default now(),
  attempts        int not null default 0,
  last_error      text,
  sent_at         timestamptz,
  skipped         boolean not null default false,
  created_at      timestamptz not null default now()
);
create index mail_outbox_pending_idx on mail_outbox (not_before) where sent_at is null and not skipped;
create unique index mail_outbox_dedupe_idx on mail_outbox (dedupe_key) where sent_at is null and not skipped and dedupe_key is not null;

-- ------------------------------------------------------------------ Webhook

-- 受信: POST /hooks/{token} でチャンネルに投稿する（Slack・Mattermost と同じ形の JSON）
create table incoming_webhook (
  id         uuid primary key,
  channel_id uuid not null references channel(id) on delete cascade,
  name       text not null,
  token_hash bytea not null unique,
  token_hint text not null,                        -- 画面に出す先頭部分
  username   text,                                 -- 表示名（既定は name）
  icon_url   text,
  icon_emoji text,                                 -- アイコン（選んだ絵文字・カスタム絵文字の :名前:）
  enabled    boolean not null default true,
  created_by uuid references app_user(id) on delete set null,
  created_at timestamptz not null default now()
);

-- 送信: イベントを JSON で POST する。channel_id が null なら全体（公開チャンネルだけ）
create table outgoing_webhook (
  id            uuid primary key,
  channel_id    uuid references channel(id) on delete cascade,
  name          text not null,
  url           text not null,
  secret        text not null,
  events        text[] not null,                   -- 空なら全イベント
  trigger_words text[] not null default '{}',     -- message.posted をこの語で始まるものに絞る（空なら絞らない）
  enabled       boolean not null default true,
  created_by    uuid references app_user(id) on delete set null,
  created_at    timestamptz not null default now()
);

create table webhook_delivery (
  id          uuid primary key,
  webhook_id  uuid not null references outgoing_webhook(id) on delete cascade,
  event       text not null,
  payload     jsonb not null,
  not_before  timestamptz not null default now(),
  attempts    int not null default 0,
  status_code int,
  last_error  text,
  done_at     timestamptz,
  failed      boolean not null default false,
  created_at  timestamptz not null default now()
);
create index webhook_delivery_pending_idx on webhook_delivery (not_before) where done_at is null and not failed;

-- ------------------------------------------------------------------ 外部サービスとの連携（OAuth）

-- stui が認可を出す側（skea などが stui に通知を送る・メッセージを読む）。認可コード: 60秒で失効、一度だけ使える
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  scope          text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);

-- stui が認可を受ける側（stui が skiwi・skawau・skea・skaka を使う）。ユーザーごとのトークン
create table integration_token (
  user_id    uuid not null references app_user(id) on delete cascade,
  provider   text not null,
  token_enc  text not null,                      -- APP_SECRET の鍵で暗号化したトークン
  created_at timestamptz not null default now(),
  primary key (user_id, provider)
);

-- 認可を始めたときの state と PKCE の code_verifier（10分で失効）
create table integration_state (
  state         text primary key,
  user_id       uuid not null references app_user(id) on delete cascade,
  provider      text not null,
  code_verifier text not null,
  return_to     text not null,
  expires_at    timestamptz not null
);

-- ------------------------------------------------------------------ 運用

-- 複数ノードで1つだけ動かす定期処理のリース
create table job_lock (
  name       text primary key,
  holder     text not null,
  expires_at timestamptz not null
);

create table audit_log (
  id       bigserial primary key,
  at       timestamptz not null default now(),
  actor_id uuid,
  via      text not null,
  action   text not null,
  target   text,
  detail   jsonb not null default '{}'
);
create index audit_log_at_idx on audit_log (at desc);
