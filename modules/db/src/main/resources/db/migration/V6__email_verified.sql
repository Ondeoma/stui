-- IdP がメールアドレスを確かめ済みか（ID トークンの email_verified。ログインのたびに更新する）。
-- メールアドレスでの結び付け（Mattermost から取り込んだアカウントの引き継ぎ・取り込みでの既存ユーザーへの結び付け）は、これが true の人だけ。
alter table app_user add column email_verified boolean not null default false;
