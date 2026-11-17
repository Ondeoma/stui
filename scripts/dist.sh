#!/usr/bin/env bash
# 配布物を作る: dist/lib/*.jar（依存＋本体）と dist/static（フロントエンド）。
# 起動: java -cp 'dist/lib/*' stui.app.Main（STATIC_DIR=dist/static）
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && { [ -d node_modules ] || npm ci; } && npm run build)
rm -rf dist && mkdir -p dist/lib
CP=$(sbt -Dsbt.supershell=false -error "export app/Runtime/fullClasspath" | tail -1)
IFS=':' read -ra ENTRIES <<< "$CP"
for e in "${ENTRIES[@]}"; do
  if [ -f "$e" ]; then cp "$e" dist/lib/
  elif [ -d "$e" ]; then
    # モジュールのクラスディレクトリを jar にまとめる（例: modules/db/target/scala-3.3.7/classes → stui-db.jar,
    # modules/shared/jvm/target/... → stui-shared.jar）
    name=$(echo "$e" | sed -E 's#.*/modules/([^/]+)/(jvm/)?target/.*#\1#')
    jar cf "dist/lib/stui-$name.jar" -C "$e" .
  fi
done
cp -R frontend/dist dist/static
echo "dist/: $(ls dist/lib | wc -l | tr -d ' ') jars"
