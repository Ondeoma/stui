-- IdP からもログアウトするときに渡す id_token_hint（OIDC でログインしたセッションだけ）
alter table session add column id_token text;
