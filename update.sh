#!/usr/bin/env bash
# Pull the latest server branch and redeploy.
set -euo pipefail
cd "$(dirname "$0")"

git pull --ff-only
docker compose up -d --build
docker image prune -f

docker compose ps
