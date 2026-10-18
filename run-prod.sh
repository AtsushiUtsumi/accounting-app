#!/bin/bash
# 本番環境で起動するための起動スクリプト。
# GHCR から最新のイメージを取得し、このアプリのコンテナだけを入れ替える(ほかのアプリのコンテナには触れない)。
# DB のデータはボリュームに残る。
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo ".env がありません。.env.example をコピーして値を設定してください。" >&2
  exit 1
fi

COMPOSE=(docker compose -f compose.prod.yaml)

"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d --remove-orphans
# 入れ替わって使われなくなった古いイメージ(タグの外れたもの)だけを消す
docker image prune -f
"${COMPOSE[@]}" ps
