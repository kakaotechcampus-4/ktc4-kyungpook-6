#!/usr/bin/env bash
# 전화 API 테스트 실행 스크립트
#   ./scripts/test.sh                        전체 테스트
#   ./scripts/test.sh -k test_runtime        특정 테스트만 (unittest -k 문법)
#
# 다른 파트(ai/·backend/·frontend/)와 같은 자리·같은 이름이다 — CI 가 이 경로를 부른다.
set -euo pipefail

# 어디서 실행하든 call/ 폴더 기준으로 동작
cd "$(dirname "$0")/.."

# -t tests: 테스트끼리 서로를 import 한다(test_runtime 이 test_verification 의 가짜를 쓴다).
#           최상위를 tests/ 로 둬야 그 import 가 풀린다.
# verification 패키지는 cwd(call/)가 sys.path 에 들어가 찾아진다.
python -m unittest discover -s tests -t tests "$@"
