-- ボイスチャンネル（Discord のような通話の部屋. 音声・画面共有は参加者どうしが WebRTC で直接送り合う. サーバーは顔ぶれと接続の取り決めの受け渡しだけ）
alter table channel add column voice boolean not null default false;

-- ボイスチャンネルにいる人（画面のタブごとの接続. 1人1か所: ほかのチャンネル・タブで入ると前の接続は外れる）.
-- 画面は15秒ごとに状態を送る（seen_at）. 45秒届かなければ、閉じたものとして外す
create table voice_member (
  conn_id    uuid primary key,
  channel_id uuid not null references channel(id) on delete cascade,
  user_id    uuid not null unique references app_user(id) on delete cascade,
  muted      boolean not null default false,
  deafened   boolean not null default false,
  sharing    boolean not null default false,
  joined_at  timestamptz not null default now(),
  seen_at    timestamptz not null default now()
);
create index voice_member_channel_idx on voice_member (channel_id);

-- WebRTC の接続の取り決め（SDP・ICE の候補）の受け渡し. 各ノードが新しい行を読み、宛先の人の画面に SSE で送る（1分で消す）
create table voice_signal (
  id        bigserial primary key,
  to_user   uuid not null,
  to_conn   uuid not null,
  from_conn uuid not null,
  data      text not null,
  at        timestamptz not null default now()
);
create index voice_signal_at_idx on voice_signal (at);
