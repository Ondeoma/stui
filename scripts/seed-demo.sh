#!/usr/bin/env bash
# 開発用: デモのチャンネル・メッセージ・スレッドを作る（DEV_LOGIN=true の環境）。使い方: scripts/seed-demo.sh [BASE_URL]
set -euo pipefail
B=${1:-http://localhost:8098}
J=$(mktemp -d)
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
req() { local c=$1; shift; if [ -n "${3:-}" ]; then curl -sS -b $c -c $c -X "$1" -H 'Content-Type: application/json' -d "$3" "$B$2"; else curl -sS -b $c -c $c -X "$1" "$B$2"; fi; }
login() { curl -sS -c "$J/$1" -X POST -H 'Content-Type: application/json' -d "{\"name\":\"$2\",\"email\":\"$3\",\"admin\":$4}" $B/auth/dev-login >/dev/null; }
login a "山田 太郎" yamada@example.com true
login b "佐藤 花子" sato@example.com false
login c "鈴木 一郎" suzuki@example.com false
SATO=$(req $J/b GET /api/me | py "d['id']"); SUZUKI=$(req $J/c GET /api/me | py "d['id']")
DEV=$(req $J/a POST /api/channels "{\"name\":\"開発\",\"kind\":\"public\",\"topic\":\"次のリリースに向けて\",\"memberIds\":[\"$SATO\",\"$SUZUKI\"]}" | py "d['id']")
NEWS=$(req $J/a POST /api/channels '{"name":"お知らせ","kind":"public","purpose":"全体へのお知らせ","memberIds":[]}' | py "d['id']")
SEC=$(req $J/a POST /api/channels "{\"name\":\"経営会議\",\"kind\":\"private\",\"memberIds\":[\"$SATO\"]}" | py "d['id']")
req $J/b POST /api/channels/$NEWS/join >/dev/null; req $J/c POST /api/channels/$NEWS/join >/dev/null
M=$(req $J/a POST /api/channels/$DEV/messages '{"body":"今日のリリース作業は **15時** から始めます。手順は https://example.com/release を見てください。"}' | py "d['id']")
req $J/b POST /api/messages/$M/replies '{"body":"了解しました。ステージングの確認は済んでいます"}' >/dev/null
req $J/c POST /api/messages/$M/replies '{"body":"DB のマイグレーションだけ先に流しておきます"}' >/dev/null
req $J/b POST /api/messages/$M/reactions '{"emoji":"👍"}' >/dev/null
req $J/c POST /api/messages/$M/reactions '{"emoji":"👍"}' >/dev/null
req $J/a POST /api/messages/$M/pin >/dev/null
req $J/b POST /api/channels/$DEV/messages '{"body":"サーバーの監視の通知がうるさいので、しきい値を見直しませんか？"}' >/dev/null
req $J/a POST /api/channels/$NEWS/messages '{"body":"# 年末年始の休み\n\n- 12/29〜1/3 はお休みです\n- 緊急の連絡は #開発 へ"}' >/dev/null
req $J/a POST /api/channels/$SEC/messages '{"body":"来期の予算案を共有します（非公開）"}' >/dev/null
D=$(req $J/b POST /api/dms "{\"userIds\":[\"$(req $J/a GET /api/me | py "d['id']")\"]}" | py "d['id']")
req $J/b POST /api/channels/$D/messages '{"body":"明日の打ち合わせ、10分遅れます 🙏"}' >/dev/null
echo "デモのデータを作りました（山田 太郎 でログインしてください）"
