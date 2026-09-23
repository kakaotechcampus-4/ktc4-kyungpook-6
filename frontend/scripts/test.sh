#!/usr/bin/env bash
# 프론트엔드 eslint 실행 스크립트
#   ./scripts/test.sh              전체 검사
#   ./scripts/test.sh --fix        자동으로 고칠 수 있는 건 고치기
set -euo pipefail

# 어디서 실행하든 frontend/ 폴더 기준으로 동작
cd "$(dirname "$0")/.."

npm run lint -- "$@"
