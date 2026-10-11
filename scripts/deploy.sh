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
#
# `up` 일 때는 frontend 를 빌드하기 전에 백엔드 Swagger 로 API 코드를 만듭니다(Orval).
#   생성 결과(frontend/src/api/generated)는 커밋하지 않으므로 이 단계 없이는 frontend 빌드가 깨집니다.
#   Swagger 주소는 BACKEND_SWAGGER_URL — 셸 환경변수 → 루트 .env → http://localhost:${BACKEND_PORT:-8080}/v3/api-docs 순으로 찾습니다.

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

compose() {
  docker compose -f "$BASE_FILE" -f "$ENV_FILE" "$@"
}

# 루트 .env 에서 값 하나만 읽는다. compose 는 .env 를 ${...} 치환에만 쓰고 이 셸에는 넣어 주지 않는다.
read_dotenv() {
  local key="$1"
  [[ -f "$ROOT_DIR/.env" ]] || return 0
  { grep -E "^${key}=" "$ROOT_DIR/.env" || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

generate_frontend_api() {
  local backend_port swagger_url spec_file
  backend_port="${BACKEND_PORT:-$(read_dotenv BACKEND_PORT)}"
  swagger_url="${BACKEND_SWAGGER_URL:-$(read_dotenv BACKEND_SWAGGER_URL)}"
  swagger_url="${swagger_url:-http://localhost:${backend_port:-8080}/v3/api-docs}"

  # 지금 떠 있는 옛 백엔드가 아니라 이번에 배포하는 백엔드의 스펙으로 만들어야 한다.
  # 그래서 백엔드를 먼저 올리고, Swagger 가 응답할 때까지 기다린다.
  echo "[deploy] 백엔드를 먼저 올립니다"
  compose up -d --build backend

  echo "[deploy] Swagger 응답 대기: $swagger_url"
  spec_file="$ROOT_DIR/frontend/.openapi-spec.json"
  local i
  for i in $(seq 1 60); do
    if curl -fsS "$swagger_url" -o "$spec_file" 2>/dev/null; then
      break
    fi
    if [[ "$i" -eq 60 ]]; then
      echo "[deploy] Swagger 가 3분 동안 응답하지 않았습니다: $swagger_url" >&2
      rm -f "$spec_file"
      exit 1
    fi
    sleep 3
  done

  # 서버에 Node 가 없어도 되도록 컨테이너에서 생성한다(frontend Dockerfile 과 같은 이미지).
  # 스펙은 위에서 호스트가 받아 둔 파일을 넘긴다 — 컨테이너 안의 localhost 는 호스트가 아니기 때문이다.
  # node_modules 는 익명 볼륨에 깔아 호스트 폴더를 건드리지 않고, 생성 파일은 호스트 사용자 소유로 돌려놓는다.
  echo "[deploy] Orval 로 frontend API 코드 생성"
  if ! docker run --rm \
    -v "$ROOT_DIR/frontend:/app" \
    -v /app/node_modules \
    -v ktc4-frontend-npm-cache:/root/.npm \
    -w /app \
    -e BACKEND_SWAGGER_URL=/app/.openapi-spec.json \
    node:24-alpine \
    sh -c "npm ci --no-audit --no-fund && npm run api:generate && chown -R $(id -u):$(id -g) src/api/generated"; then
    rm -f "$spec_file"
    exit 1
  fi
  rm -f "$spec_file"
}

if [[ "$1" == "up" ]]; then
  generate_frontend_api
fi

exec docker compose -f "$BASE_FILE" -f "$ENV_FILE" "$@"
