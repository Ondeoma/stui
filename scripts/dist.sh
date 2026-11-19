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
  if [ -f "$e" ]; then cp "$e" dist/lib/
  elif [ -d "$e" ]; then
    # モジュールのクラスディレクトリを jar にまとめる（例: modules/db/target/scala-3.3.7/classes → <名前>-db.jar,
    # modules/shared/jvm/target/...・modules/shared/.jvm/target/... → <名前>-shared.jar）
    name=$(echo "$e" | sed -E 's#.*/modules/([^/]+)/(\.?jvm/)?target/.*#\1#')
    jar cf "dist/lib/$APP-$name.jar" -C "$e" .
  fi
done
cp -R frontend/dist dist/static
echo "dist/: $(ls dist/lib | wc -l | tr -d ' ') jars"
