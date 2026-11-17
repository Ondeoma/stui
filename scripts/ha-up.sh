#!/usr/bin/env bash
# 3ノード試験環境を起動する（初回はイメージをビルド）。
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/dist.sh
# ScalaS3 はソースからのビルドに時間がかかるので、開発環境（docker-compose.dev.yml）で作ったイメージがあれば使う
docker image inspect stui-scalas3:dev >/dev/null 2>&1 || docker compose -f docker-compose.ha.yml build s3
docker compose -f docker-compose.ha.yml build pg1 app1
docker compose -f docker-compose.ha.yml up -d
echo "waiting for HAProxy (http://localhost:8099/healthz) ..."
for i in $(seq 1 90); do curl -sf http://localhost:8099/healthz >/dev/null && break; sleep 2; done
docker compose -f docker-compose.ha.yml exec -T pg1 patronictl -c /etc/patroni/patroni.yml list || true
curl -s http://localhost:8099/healthz; echo
