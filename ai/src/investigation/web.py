"""웹검색 2차 조사 — `Investigator` 구현.

    관측 수집(`web_research.py`, LLM 1회 + 형식 이탈 시 재시도)
    + 카카오맵 확인(`kakao_map.py`, LLM 없음 — 카카오 응답은 모델에 넘기지 않는다)
    → 규칙 분류(`classify.py`)

에이전트 프레임워크나 도구 루프 없이 이 두 단계만 돈다. 모델이 할 일은 "웹에서 본 값을
근거와 함께 적어 오기"뿐이고, 판단은 규칙이 한다.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Sequence
from typing import Protocol

from src.investigation.classify import classify
from src.investigation.models import InvestigationTarget, PlaceCheck, StoreFinding
from src.investigation.web_research import ResearchParseError, ResearchProvider

# 그라운딩 검색을 켜면 형식을 강제할 수 없고, 그라운딩이 빈 채로 오기도 한다.
LLM_ATTEMPTS = 2

# Vertex 가 429(요청 한도 초과)를 내면 기다렸다 다시 부른다. 실측에서 연달아 부르다 한 번 났다.
# 기다린 뒤에도 나면 그 건은 실패로 남긴다 — 무한히 매달리면 배치 전체가 멈춘다.
RATE_LIMIT_WAITS_SECONDS = (5.0, 15.0)

logger = logging.getLogger(__name__)


def _is_rate_limited(error: Exception) -> bool:
    """벤더 SDK 예외가 429 인가. SDK 를 import 하지 않고 `code` 만 본다."""
    return getattr(error, "code", None) == 429


class PlaceChecker(Protocol):
    def check(self, target: InvestigationTarget) -> PlaceCheck: ...


class WebInvestigator:
    def __init__(
        self,
        provider: ResearchProvider,
        *,
        place_checker: PlaceChecker | None = None,
        rate_limit_waits: Sequence[float] = RATE_LIMIT_WAITS_SECONDS,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._provider = provider
        self._place_checker = place_checker
        self._rate_limit_waits = rate_limit_waits
        self._sleep = sleep

    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        """한 건을 조사한다. 끝까지 안 되면 예외를 올려 건별 실패로 남긴다."""
        parse_failures = 0
        waits = iter(self._rate_limit_waits)
        while True:
            try:
                result = self._provider.research(target)
            except ResearchParseError as e:
                parse_failures += 1
                if parse_failures >= LLM_ATTEMPTS:
                    raise ResearchParseError(f"응답이 {LLM_ATTEMPTS}번 모두 쓸 수 없었습니다: {e}") from e
                continue
            except Exception as e:
                wait = next(waits, None) if _is_rate_limited(e) else None
                if wait is None:
                    raise
                logger.info("요청 한도 초과 — %s초 뒤 다시 부릅니다 (storeId=%s)", wait, target.store_id)
                self._sleep(wait)
                continue
            place = self._place_checker.check(target) if self._place_checker else None
            return classify(target, result, place)
