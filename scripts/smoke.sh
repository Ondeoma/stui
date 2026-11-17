#!/usr/bin/env bash
# 開発環境（DEV_LOGIN=true）で API を一通り呼ぶ確認用スクリプト。使い方: scripts/smoke.sh [BASE_URL]
set -euo pipefail
B=${1:-http://localhost:8098}
J=$(mktemp -d)
CA="$J/a" CB="$J/b"
req() { # cookie method path [json]
  local c=$1 m=$2 p=$3 d=${4:-}
  if [ -n "$d" ]; then curl -sS -b "$c" -c "$c" -X "$m" -H 'Content-Type: application/json' -d "$d" "$B$p"
  else curl -sS -b "$c" -c "$c" -X "$m" "$B$p"; fi
}
jqv() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
echo "== login";          req $CA POST /auth/dev-login '{"name":"山田 太郎","email":"yamada@example.com","admin":true}' >/dev/null
                          req $CB POST /auth/dev-login '{"name":"佐藤 花子","email":"sato@example.com"}' >/dev/null
A=$(req $CA GET /api/me | jqv "d['id']"); S=$(req $CB GET /api/me | jqv "d['id']")
N="スモーク$(date +%H%M%S)"
echo "== channel $N";     C=$(req $CA POST /api/channels "{\"name\":\"$N\",\"kind\":\"public\",\"topic\":\"確認用\",\"memberIds\":[]}" | jqv "d['id']"); echo $C
echo "== join (佐藤)";     req $CB POST /api/channels/$C/join | jqv "d['member']"
echo "== post";           M=$(req $CA POST /api/channels/$C/messages "{\"body\":\"障害の**報告**です https://example.com\"}" | jqv "d['id']")
echo "== mention";        req $CA POST /api/channels/$C/messages "{\"body\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"mention\",\"attrs\":{\"id\":\"$S\",\"label\":\"佐藤 花子\"}},{\"type\":\"text\",\"text\":\" 確認お願いします\"}]}]}}" | jqv "d['html']"
echo "== reply (佐藤)";    req $CB POST /api/messages/$M/replies '{"body":"了解しました"}' | jqv "d['rootId']"
echo "== thread";         req $CA GET /api/messages/$M/thread | jqv "(d['root']['replyCount'], [r['author']['name'] for r in d['replies']], d['following'])"
echo "== reaction";       req $CB POST /api/messages/$M/reactions '{"emoji":"👍"}' | jqv "[(r['emoji'], len(r['users'])) for r in d]"
echo "== pin";            req $CA POST /api/messages/$M/pin >/dev/null; req $CA GET /api/channels/$C/pins | jqv "len(d)"
echo "== save";           req $CB POST /api/messages/$M/save >/dev/null; req $CB GET /api/saved | jqv "len(d) >= 1"
echo "== unread (佐藤)";   req $CB GET /api/channels | jqv "[(c['unread'], c['mentions']) for c in d if c['id']=='$C']"
echo "== notifications";  req $CB GET "/api/notifications?unread=true" | jqv "[n['title'] for n in d['items']][:3]"
echo "== read";           req $CB POST /api/channels/$C/read '{"seq":0}'; req $CB GET /api/channels | jqv "[(c['unread'], c['mentions']) for c in d if c['id']=='$C']"
echo "== threads (山田)";  req $CA GET /api/threads | jqv "[(t['channel']['name'], t['unread']) for t in d][:3]"
echo "== dm";             D=$(req $CA POST /api/dms "{\"userIds\":[\"$S\"]}" | jqv "d['id']"); req $CA POST /api/channels/$D/messages '{"body":"DM です"}' >/dev/null
                          req $CB GET /api/channels | jqv "[(c['kind'], [u['name'] for u in c['dmUsers']], c['unread']) for c in d if c['id']=='$D']"
enc() { python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1"; }
echo "== search";         req $CA GET "/api/search?q=$(enc "報告 in:#$N")" | jqv "(d['total'], d['error'], [h['snippet'] for h in d['hits']])"
echo "== search filters"; req $CA GET "/api/search?q=has:link%20from:me" | jqv "(d['total'] >= 1, d['error'])"
echo "== bookmark";       req $CA POST /api/channels/$C/bookmarks '{"title":"手順書","url":"https://example.com/doc","emoji":"📘"}' | jqv "[b['title'] for b in d]"
echo "== reminder";       AT=$(python3 -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=2)).isoformat())")
                          req $CA POST /api/reminders "{\"text\":\"スモークのリマインダー\",\"messageId\":\"$M\",\"remindAt\":\"$AT\"}" | jqv "len(d)"
echo "== scheduled";      req $CA POST /api/scheduled "{\"channelId\":\"$C\",\"body\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"おはようございます\"}]}]},\"frequency\":\"weekdays\",\"runTime\":\"09:00\",\"enabled\":true}" | jqv "[(x['frequency'], x['nextRun'] is not None) for x in d][:1]"
echo "== incoming hook";  H=$(req $CA POST /api/hooks/incoming "{\"channelId\":\"$C\",\"name\":\"CI\",\"username\":\"ビルド\",\"enabled\":true}")
URL=$(echo "$H" | jqv "d['url']"); curl -sS -X POST -H 'Content-Type: application/json' -d '{"text":"ビルドが成功しました <https://ci.example/1|#1>","attachments":[{"title":"詳細","text":"全テスト通過","fields":[{"title":"時間","value":"3分"}]}]}' "$B/hooks/${URL##*/hooks/}" | jqv "d['ok']"
echo "== outgoing hook";  req $CA POST /api/hooks/outgoing "{\"channelId\":\"$C\",\"name\":\"受け口\",\"url\":\"http://localhost:9/hook\",\"events\":[\"message.created\"],\"triggerWords\":[],\"enabled\":true}" | jqv "len(d)"
echo "== edit/delete";    X=$(req $CA POST /api/channels/$C/messages '{"body":"消す"}' | jqv "d['id']"); req $CA PUT /api/messages/$X '{"body":"直した"}' | jqv "d['editedAt'] is not None"
                          req $CA DELETE /api/messages/$X; req $CA GET /api/channels/$C/messages | jqv "[m['kind'] + ':' + (m['props'].get('username') or (m['author'] or {}).get('name','')) for m in d['messages']]"
echo "== changes";        req $CB GET "/api/changes?since=0" | jqv "len(d['events']) > 0"
echo "== topic";          req $CA PATCH /api/channels/$C '{"topic":"新しいトピック"}' | jqv "d['topic']"
echo "== mcp";            curl -sS -b $CA -X POST $B/api/me/api-keys -H 'Content-Type: application/json' -d '{"name":"smoke","scopes":["read","write"]}' > $J/key
KEY=$(cat $J/key | jqv "d['key']")
curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jqv "[t['name'] for t in d['result']['tools']]"
curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"read_channel\",\"arguments\":{\"channel\":\"$N\",\"limit\":3}}}" | jqv "d['result']['content'][0]['text']"
curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"post_message\",\"arguments\":{\"channel\":\"@佐藤 花子\",\"text\":\"MCP からの DM\"}}}" | jqv "d['result']['content'][0]['text']"
sleep 25
echo "== reminder fired"; req $CA GET /api/channels | jqv "[(c['dmUsers'][0]['name'], c['unread']) for c in d if c['kind']=='dm' and c['dmUsers'] and c['dmUsers'][0]['bot']]"
echo "ok"
