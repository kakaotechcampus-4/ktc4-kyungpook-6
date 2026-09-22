"""조사 로직이 꽂히는 자리.

구현체는 PROMPT-69 에서 온다. 그전까지 서버는 `InvestigatorUnavailable` 을 받아
503 으로 답한다 — **아직 못 한다는 사실을 200 으로 감추지 않는다.**
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from src.investigation.models import InvestigationTarget, StoreFinding


class InvestigatorUnavailable(RuntimeError):
    """조사 구현이 아직 연결되지 않았거나, 필요한 자격증명이 없을 때."""


@runtime_checkable
class Investigator(Protocol):
    """가게 한 건을 조사해 결과를 돌려준다.

    한 건씩 받는 이유 — 한 건이 실패해도 나머지는 계속 처리해야 하고,
    호출하는 쪽이 건별 실패를 `StoreFinding.failure` 로 기록할 수 있어야 한다.

    `runtime_checkable` 을 붙여 둔 건 구현체가 이 모양을 지키는지 테스트에서
    `isinstance` 로 확인하기 위해서다. 메서드 **이름만** 보므로 인자·반환 타입까지
    보장하지는 않는다 — 그건 타입 검사기와 테스트가 할 일이다.
    """

    def investigate(self, target: InvestigationTarget) -> StoreFinding: ...


class UnavailableInvestigator:
    """기본 구현. 부르면 바로 실패한다."""

    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        raise InvestigatorUnavailable(
            "조사 구현이 아직 연결되지 않았습니다 (PROMPT-69 사업자등록번호 반환 함수 대기 중)"
        )
