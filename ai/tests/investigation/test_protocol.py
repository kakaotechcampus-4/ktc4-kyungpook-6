"""조사 구현이 꽂히는 자리 — 기본 구현과 Mock 이 계약을 지키는지 본다."""

from __future__ import annotations

import pytest

from src.investigation import (
    InvestigationTarget,
    Investigator,
    InvestigatorUnavailable,
    UnavailableInvestigator,
)
from src.investigation.mock import MockInvestigator

TARGET = InvestigationTarget(store_id=1, name="성심당", address="대전 중구 은행동")


def test_기본_구현은_쓸_수_없다고_알린다():
    """구현이 없는 상태를 빈 결과로 감추지 않는다 — 서버가 이걸 503 으로 바꾼다."""
    with pytest.raises(InvestigatorUnavailable, match="연결되지 않았습니다"):
        UnavailableInvestigator().investigate(TARGET)


@pytest.mark.parametrize(
    "investigator", [UnavailableInvestigator(), MockInvestigator()], ids=["기본", "Mock"]
)
def test_구현체는_Investigator_를_만족한다(investigator):
    """앞으로 올 조사 구현도 이 모양이면 그대로 꽂힌다."""
    assert isinstance(investigator, Investigator)


class TestMockInvestigator:
    def test_알려진_케이스는_근거를_돌려준다(self):
        found = MockInvestigator().investigate(TARGET)

        assert found.failure is None
        assert found.evidences, "근거 없이 결과만 돌려주면 사람이 검증할 수 없다"

    def test_모르는_가게는_실패로_표시한다(self):
        found = MockInvestigator().investigate(
            InvestigationTarget(store_id=2, name="없는가게")
        )

        assert found.evidences == []
        assert found.failure == "근거를 찾지 못했습니다"

    def test_결과에_요청한_storeId_를_그대로_담는다(self):
        """부르는 쪽이 요청과 결과를 짝지을 수 있어야 한다."""
        found = MockInvestigator().investigate(
            InvestigationTarget(store_id=99, name="없는가게")
        )

        assert found.store_id == 99
