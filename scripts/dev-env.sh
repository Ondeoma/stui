# 開発用の環境変数（source scripts/dev-env.sh）。docker-compose.dev.yml の DB・ScalaS3・Mailpit を使う
export DATABASE_URL=jdbc:postgresql://localhost:54379/stui DATABASE_USER=stui DATABASE_PASSWORD=stui
export PUBLIC_URL=${PUBLIC_URL:-http://localhost:5178} HTTP_PORT=8098
export DEV_LOGIN=true
export S3_ENDPOINT=http://localhost:3920 S3_BUCKET=stui S3_ACCESS_KEY=stui S3_SECRET_KEY=stuistuistuistui
export SMTP_HOST=localhost SMTP_PORT=1027 SMTP_TLS=none MAIL_FROM=stui@example.com MAIL_DELAY_MINUTES=1
export WEBHOOK_ALLOW_HTTP=true WEBHOOK_ALLOW_PRIVATE=true
# stui を使う兄弟のサービス（通知を送る・メッセージの名前を出す。開発用の値。本番では secret をランダムな値にする）
export OAUTH_CLIENTS='[{"id":"skea","secret":"skea-dev-secret-0123456789","redirectUri":"http://localhost:8090/api/integrations/stui/callback","name":"skea","scopes":["read","write"]},{"id":"skiwi","secret":"skiwi-dev-secret-0123456789","redirectUri":"http://localhost:8080/api/integrations/stui/callback","name":"skiwi","scopes":["read","write"]}]'
# stui から兄弟のサービスを使う向き（相手に client_id=stui として登録する。URL は各サービスの画面の開発サーバー）
export SKIWI_URL=http://localhost:8080 SKIWI_CLIENT_ID=stui SKIWI_CLIENT_SECRET=stui-dev-secret-0123456789
export SKAWAU_URL=http://localhost:5176 SKAWAU_CLIENT_ID=stui SKAWAU_CLIENT_SECRET=stui-dev-secret-0123456789
export SKEA_URL=http://localhost:5174 SKEA_CLIENT_ID=stui SKEA_CLIENT_SECRET=stui-dev-secret-0123456789
export SKAKA_URL=http://localhost:5177 SKAKA_CLIENT_ID=stui SKAKA_CLIENT_SECRET=stui-dev-secret-0123456789
