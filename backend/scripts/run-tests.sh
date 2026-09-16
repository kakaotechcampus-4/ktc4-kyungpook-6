#!/usr/bin/env bash
# 백엔드 테스트 전체 실행 스크립트.
# Testcontainers로 실제 Postgres에 붙는 통합 테스트라 Docker가 실행 중이어야 한다.
set -euo pipefail

cd "$(dirname "$0")/.."  # backend/ 로 이동 (스크립트 위치 기준)

./gradlew test
