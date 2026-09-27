#!/usr/bin/env bash
# AI 테스트 실행 스크립트
#   ./scripts/test.sh                  전체 테스트
#   ./scripts/test.sh -k web_search    특정 테스트만 (pytest -k 문법)
set -euo pipefail

# 어디서 실행하든 ai/ 폴더 기준으로 동작
cd "$(dirname "$0")/.."

uv run pytest tests/ "$@"
