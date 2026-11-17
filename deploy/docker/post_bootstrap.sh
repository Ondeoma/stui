#!/bin/sh
# クラスタ初回作成時にプライマリで1度だけ実行される: アプリ用ロール・DB・PGroonga拡張を作る
set -e
psql -h /tmp -U postgres -v ON_ERROR_STOP=1 <<SQL
create role stui login password '${STUI_DB_PASSWORD:-stui}';
create database stui owner stui;
\c stui
create extension if not exists pgroonga;
SQL
