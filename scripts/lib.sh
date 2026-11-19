# scripts/*.sh の共通部分（source して使う. リポジトリの直下で読み込むこと）
# このファイルは sbirds-lib/scripts から配っている. 直接直さず、sbirds-lib で直して `sbirds-lib/tools/sbirds sync-scripts` で配ること

# プロジェクトの名前（modules/app/src/main/scala の下のパッケージ名. 例: skakapo）
APP=$(basename "$(ls -d modules/app/src/main/scala/*/ | head -1)")

# ポートで待ち受けているプロセスを止め、ポートが空き、プロセスが終わるまで待つ（最大 30 秒）
free_port() {
  local pid
  pid=$(lsof -tiTCP:"$1" -sTCP:LISTEN || true)
  [ -n "$pid" ] || return 0
  kill $pid
  for _ in $(seq 1 60); do
    { lsof -tiTCP:"$1" -sTCP:LISTEN >/dev/null || kill -0 $pid 2>/dev/null; } || return 0
    sleep 0.5
  done
}

# sbt のタスクを裏で起動し、ログに準備完了の文言が出るまで待つ: run_sbt <タスク> <ログ> <準備完了の正規表現> <待つ秒数>
run_sbt() {
  # 前のログは別名で残す（止めた古いプロセスの出力が新しいログに混ざらないように）
  [ -f "$2" ] && mv "$2" "$2.old"
  nohup sbt -batch -no-colors "$1" > "$2" 2>&1 &
  for _ in $(seq 1 "$4"); do
    grep -qE "$3" "$2" && { echo "started"; return 0; }
    grep -qE 'nonzero exit|\[error\]' "$2" && { tail -30 "$2"; return 1; }
    sleep 1
  done
  echo "timeout"; return 1
}
