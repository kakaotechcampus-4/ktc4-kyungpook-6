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

# Docker Engine 29 는 API 1.40 미만을 끊었는데, Testcontainers 1.20.4 가 쓰는 docker-java 는
# 1.32 로 말을 건다("client version 1.32 is too old"). 그러면 컨테이너를 쓰는 테스트가 전부
# initializationError 로 죽는다. docker-java 가 쓸 API 버전을 올려 준다.
# 이미 정해 둔 값이 있으면 그것을 쓴다.
export DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

gradle test "$@"
