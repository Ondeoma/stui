#!/usr/bin/env bash
# 配布物を作る: dist/lib/*.jar（依存＋本体）と dist/static（フロントエンド）。
# 起動: java -cp 'dist/lib/*' <名前>.app.Main（STATIC_DIR=dist/static）
# このファイルは sbirds-lib/scripts から配っている. 直接直さず、sbirds-lib で直して `sbirds-lib/tools/sbirds sync-scripts` で配ること
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib.sh
(cd frontend && { [ -d node_modules ] || npm ci; } && npm run build)
rm -rf dist && mkdir -p dist/lib
CP=$(sbt -Dsbt.supershell=false -error "export app/Runtime/fullClasspath" | tail -1)
IFS=':' read -ra ENTRIES <<< "$CP"
for e in "${ENTRIES[@]}"; do
  if [ -f "$e" ]; then out="dist/lib/$(basename "$e")"
  elif [ -d "$e" ]; then
    # モジュールのクラスディレクトリを jar にまとめる（例: modules/db/target/scala-3.9.0/classes → <名前>-db.jar,
    # modules/shared/jvm/target/...・modules/shared/.jvm/target/... → <名前>-shared.jar）.
    # このリポジトリの外のもの（ソースで参照している sbirds-lib）は sbirds-<モジュール>.jar にする
    # （同じ名前のモジュール〈shared・db など〉がアプリの jar を上書きしないように）
    name=$(echo "$e" | sed -E 's#.*/modules/([^/]+)/(\.?jvm/)?target/.*#\1#')
    case "$e" in "$PWD"/*) prefix=$APP ;; *) prefix=sbirds ;; esac
    out="dist/lib/$prefix-$name.jar"
  else continue
  fi
  # 同じ名前の jar を黙って上書きしない（クラスが欠けて起動できなくなる）
  [ ! -e "$out" ] || { echo "dist: $(basename "$out") が重なっています（$e）" >&2; exit 1; }
  if [ -f "$e" ]; then cp "$e" "$out"; else jar cf "$out" -C "$e" .; fi
done
cp -R frontend/dist dist/static
echo "dist/: $(ls dist/lib | wc -l | tr -d ' ') jars"
