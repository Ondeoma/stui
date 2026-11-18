-- ほかのチャット（Mattermost）からの取り込み。取り込み元の ID と stui の ID の対応
-- （取り込みをやり直したり、あとから追加で取り込んだりしても、同じものを二重に作らない）
create table import_map (
  source     text not null,                     -- mattermost
  kind       text not null,                     -- user / channel / post / file / emoji
  source_id  text not null,
  target_id  uuid not null,
  created_at timestamptz not null default now(),
  primary key (source, kind, source_id)
);
