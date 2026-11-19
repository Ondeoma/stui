#!/usr/bin/env bash
# 開発用: サーバー（scripts/dev-env.sh の HTTP_PORT）を止めて起動し直し、起動を待ってから戻る（ログは $LOG、既定 /tmp/<名前>-dev.log）
# scripts/dev-idp.sh があるプロジェクトでは、OIDC=dev scripts/dev-restart.sh で開発用の IdP を使った OIDC のログインになる
# このファイルは sbirds-lib/scripts から配っている. 直接直さず、sbirds-lib で直して `sbirds-lib/tools/sbirds sync-scripts` で配ること
cd "$(dirname "$0")/.."
. scripts/lib.sh
. scripts/dev-env.sh
LOG=${LOG:-/tmp/$APP-dev.log}
free_port "$HTTP_PORT"
run_sbt app/run "$LOG" 'listening on' 180
