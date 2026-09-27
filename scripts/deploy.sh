#!/usr/bin/env bash
# docker-compose.yml(base) + docker-compose.<env>.yml 을 병합해서 docker compose 명령을 실행합니다.
#
# 사용법:
#   scripts/deploy.sh <dev|prod> [docker compose 명령...]
#
# 예시:
#   scripts/deploy.sh dev up -d --build
#   scripts/deploy.sh prod up -d --build --remove-orphans
#   scripts/deploy.sh dev down
#   scripts/deploy.sh dev logs -f backend

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENVIRONMENT="${1:-}"
shift || true

if [[ "$ENVIRONMENT" != "dev" && "$ENVIRONMENT" != "prod" ]]; then
  echo "사용법: $0 <dev|prod> [docker compose 명령...]" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  echo "docker compose 명령을 지정하세요. 예: $0 $ENVIRONMENT up -d --build" >&2
  exit 1
fi

BASE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="$ROOT_DIR/docker-compose.${ENVIRONMENT}.yml"

exec docker compose -f "$BASE_FILE" -f "$ENV_FILE" "$@"
