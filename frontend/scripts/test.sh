#!/usr/bin/env bash
# 프론트엔드 검사 스크립트. CI(.github/workflows/test.yml 의 frontend-test)가 이걸 부른다.
#   ./scripts/test.sh              API 코드 생성(없을 때) → eslint → 타입체크 → 단위 테스트
#   ./scripts/test.sh --fix        eslint 가 고칠 수 있는 건 고치기 (인자는 eslint 에만 넘긴다)
#
# 예전에는 eslint 만 돌아서 vitest 테스트가 CI 에서 한 번도 돌지 않았다(PROMPT-126).
set -euo pipefail

# 어디서 실행하든 frontend/ 폴더 기준으로 동작
cd "$(dirname "$0")/.."

# Orval 생성 결과는 커밋하지 않으므로 깨끗한 클론(CI)에는 없다. 없으면 BACKEND_SWAGGER_URL 로 만든다.
# 이미 있으면 건드리지 않는다 — 로컬에서 원하는 백엔드로 만들어 둔 코드를 덮어쓰지 않기 위해서다.
if [[ ! -d src/api/generated ]]; then
  echo "[test] src/api/generated 가 없어 Orval 로 생성합니다"
  if ! npm run api:generate; then
    echo "[test] API 코드 생성 실패 — 코드 문제가 아니라 BACKEND_SWAGGER_URL 이 비었거나" >&2
    echo "       그 백엔드가 응답하지 않는 것일 수 있습니다. CI 라면 secrets.BACKEND_SWAGGER_URL 과 dev 서버 상태를 확인하세요." >&2
    exit 1
  fi
fi

npm run lint -- "$@"
npx tsc -b
npm test
