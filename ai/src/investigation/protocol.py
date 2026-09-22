"""조사 로직이 꽂히는 자리.

구현체는 PROMPT-69 에서 온다. 그전까지 서버는 `InvestigatorUnavailable` 을 받아
503 으로 답한다 — **아직 못 한다는 사실을 200 으로 감추지 않는다.**
"""

from __future__ import annotations

from typing import Protocol

from src.investigation.models import InvestigationTarget, StoreFinding


class InvestigatorUnavailable(RuntimeError):
    """조사 구현이 아직 연결되지 않았거나, 필요한 자격증명이 없을 때."""


class Investigator(Protocol):
    """가게 한 건을 조사해 결과를 돌려준다.

    한 건씩 받는 이유 — 한 건이 실패해도 나머지는 계속 처리해야 하고,
    호출하는 쪽이 건별 실패를 `StoreFinding.failure` 로 기록할 수 있어야 한다.
    """

    def investigate(self, target: InvestigationTarget) -> StoreFinding: ...


class UnavailableInvestigator:
    """기본 구현. 부르면 바로 실패한다."""

    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        raise InvestigatorUnavailable(
            "조사 구현이 아직 연결되지 않았습니다 (PROMPT-69 사업자등록번호 반환 함수 대기 중)"
        )
