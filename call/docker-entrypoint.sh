#!/bin/sh
# 서버를 띄우기 전에 공용 TTS 캐시를 채운다.
# 캐시가 비어 있으면 첫 통화 중에 합성이 일어나 응답이 늦어지므로, 그 비용을 시작 시점으로 옮긴다.
# 이미 완성된 캐시는 API 호출 없이 건너뛰고, 더 이상 쓰이지 않는 항목은 정리한다.
set -eu

if [ "${PREBUILD_TTS_CACHE:-1}" != "0" ]; then
    if ! python -m verification.cache_tts; then
        # TTS 제공자 장애로 서버 전체를 못 뜨게 하지는 않는다 — 통화 중 합성으로 대체된다.
        echo '경고: 공용 TTS 캐시 준비 실패. 첫 통화에서 합성 지연이 발생할 수 있습니다.' >&2
    fi
fi

exec "$@"
