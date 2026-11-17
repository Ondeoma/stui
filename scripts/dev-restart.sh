#!/usr/bin/env bash
# 開発用: 8098 番のサーバーを止めて、起動し直す（ログは $LOG、既定 /tmp/stui-dev.log）。起動を待ってから戻る
cd "$(dirname "$0")/.."
LOG=${LOG:-/tmp/stui-dev.log}
pid=$(lsof -tiTCP:${HTTP_PORT:-8098} -sTCP:LISTEN || true)
if [ -n "$pid" ]; then
  kill $pid
  # 前のサーバーが止まってから（ログに前の出力が混ざらないよう）起動する
  for i in $(seq 1 30); do kill -0 $pid 2>/dev/null || break; sleep 1; done
  sleep 2
fi
rm -f "$LOG"
. scripts/dev-env.sh
nohup sbt -batch -no-colors app/run > "$LOG" 2>&1 &
for i in $(seq 1 180); do
  grep -qE 'listening on' "$LOG" && { echo "started"; exit 0; }
  grep -qE 'nonzero exit|\[error\]' "$LOG" && { tail -30 "$LOG"; exit 1; }
  sleep 1
done
echo "timeout"; exit 1
