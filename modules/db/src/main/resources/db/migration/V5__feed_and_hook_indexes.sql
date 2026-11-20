-- 画面への知らせ（各ノードが0.5秒ごとに「at > ?」で通知を確かめる）と、古い通知の削除で使う
create index notification_at_idx on notification (at);

-- 送信の Webhook の履歴の一覧（webhook_id で絞って新しい順）と、Webhook を消すときの連鎖の削除で使う
create index webhook_delivery_webhook_idx on webhook_delivery (webhook_id, created_at desc);

-- メッセージを消すときに、保存した人の行を message_id で消す（主キーは user_id が先なので使えない）
create index saved_message_message_idx on saved_message (message_id);
