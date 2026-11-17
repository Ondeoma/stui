#!/usr/bin/env bash
# 3ノード試験環境（docker-compose.ha.yml）の障害試験。
#  1. アプリ1台を止めても、HAProxy 経由で投稿・読み出しができる
#  2. DB のプライマリを止めても、フェイルオーバー後に投稿でき、止める前のメッセージも残っている
set -euo pipefail
cd "$(dirname "$0")/.."
B=${BASE:-http://localhost:8099}
DC="docker compose -f docker-compose.ha.yml"
C=$(mktemp)
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
login() { curl -sf -c $C -X POST -H 'Content-Type: application/json' -d '{"name":"HA 試験","admin":true}' $B/auth/dev-login >/dev/null; }
post() { curl -sf -b $C -X POST -H 'Content-Type: application/json' -d "{\"body\":\"$1\"}" $B/api/channels/$CH/messages | py "d['id']"; }
retry() { for i in $(seq 1 60); do if out=$("$@" 2>/dev/null); then echo "$out"; return 0; fi; sleep 2; done; return 1; }

login
NAME="HA試験$(date +%H%M%S)"
CH=$(curl -sf -b $C -X POST -H 'Content-Type: application/json' -d "{\"name\":\"$NAME\",\"kind\":\"public\",\"memberIds\":[]}" $B/api/channels | py "d['id']")
echo "channel $NAME"
M0=$(post "停止前")

echo "== アプリ app1 を停止"
$DC stop app1 >/dev/null
sleep 5
for i in 1 2 3 4 5 6; do post "app1 停止中 $i" >/dev/null; done
echo "OK: app1 停止中も6件投稿できた"
$DC start app1 >/dev/null

echo "== DB プライマリを停止"
LEADER=$($DC exec -T pg1 patronictl -c /etc/patroni/patroni.yml list -f json | py "[m['Member'] for m in d if m['Role']=='Leader'][0]")
echo "leader: $LEADER"
$DC stop "$LEADER" >/dev/null
T0=$(date +%s)
M1=$(retry post "フェイルオーバー後")
echo "OK: $(( $(date +%s) - T0 )) 秒後に投稿できた ($M1)"
retry curl -sf -b $C $B/api/messages/$M0 | py "'OK: 停止前のメッセージが残っている: ' + d['id']"
$DC start "$LEADER" >/dev/null
sleep 20
$DC exec -T pg1 patronictl -c /etc/patroni/patroni.yml list || true
N=$(curl -sf -b $C "$B/api/channels/$CH/messages?limit=100" | py "len([m for m in d['messages'] if m['kind'] == 'user'])")
echo "メッセージ数: ${N}（期待 8）"
[ "$N" = "8" ] && echo "ALL OK"
