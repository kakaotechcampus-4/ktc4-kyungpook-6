#!/usr/bin/env bash
# 백엔드 테스트 실행 스크립트
#   ./scripts/test.sh                                   전체 테스트
#   ./scripts/test.sh --tests '*BusinessServiceTest'    특정 테스트만
set -euo pipefail

# 어디서 실행하든 backend/ 폴더 기준으로 동작
cd "$(dirname "$0")/.."

# 맥에서 기본 JDK 가 21 이 아닐 수 있어 21 로 맞춘다. 리눅스·CI 는 기존 JAVA_HOME 을 그대로 쓴다.
if [[ "$(uname)" == "Darwin" ]]; then
  if JAVA21="$(/usr/libexec/java_home -v 21)"; then
    export JAVA_HOME="$JAVA21"
  else
    echo "⚠️  Java 21 을 찾지 못해 현재 JAVA_HOME 으로 실행합니다" >&2
  fi
fi

gradle test "$@"
