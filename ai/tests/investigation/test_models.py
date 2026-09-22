"""조사 요청·결과 계약 — 엔드포인트를 거치지 않고 모델만 본다.

여기서 고정하는 건 **PROMPT-69 구현이 기대해도 되는 것**이다.
서버 테스트는 HTTP 경로를 보지만, 구현체는 이 모델을 직접 들고 쓴다.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from src.investigation import (
    Evidence,
    InvestigationResponse,
    InvestigationTarget,
    StoreFinding,
)


class TestInvestigationTarget:
    def test_백엔드_응답_필드명으로_만든다(self):
        """`/investigation-targets` 가 돌려준 값을 그대로 넣을 수 있어야 한다."""
        target = InvestigationTarget.model_validate(
            {
                "storeId": 1,
                "name": "예시분식",
                "addressRoad": "가상특별시 예시구 샘플로 123",
                "bizNo": "1234567890",
            }
        )

        assert (target.store_id, target.address, target.biz_no) == (
            1,
            "가상특별시 예시구 샘플로 123",
            "1234567890",
        )

    def test_파이썬_필드명으로도_만든다(self):
        """구현체가 StoreCheck 를 받아 직접 조립할 때 쓰는 경로다."""
        target = InvestigationTarget(store_id=1, name="예시분식", address="대전 중구")

        assert target.store_id == 1 and target.address == "대전 중구"

    def test_주소와_사업자번호는_없어도_된다(self):
        """주소가 비어 있는 가게도 조사 대상이 된다 — 없는 채로 넘어가야 한다."""
        target = InvestigationTarget(store_id=1, name="예시분식")

        assert target.address is None and target.biz_no is None

    @pytest.mark.parametrize(
        "payload", [{"name": "이름만"}, {"storeId": 1}], ids=["storeId_없음", "name_없음"]
    )
    def test_식별에_필요한_값이_빠지면_거부한다(self, payload):
        with pytest.raises(ValidationError):
            InvestigationTarget.model_validate(payload)

    def test_모르는_필드는_버린다(self):
        """백엔드가 필드를 더 내려줘도 깨지지 않아야 한다."""
        target = InvestigationTarget.model_validate(
            {"storeId": 1, "name": "예시분식", "ntsStatus": "CLOSED"}
        )

        assert target.store_id == 1


class TestStoreFinding:
    def test_실패_결과는_storeId_만으로_만든다(self):
        """조사가 실패해도 결과에서 빼지 않는다 — 최소한의 형태가 성립해야 한다."""
        found = StoreFinding(storeId=1, failure="후보를 찾지 못했습니다")

        assert found.official_name is None
        assert found.unambiguous is False
        assert found.evidences == []

    def test_성공_결과는_alias_로_직렬화된다(self):
        """응답 필드명이 백엔드와 같은 캐멀케이스로 나가야 한다."""
        found = StoreFinding(
            storeId=1,
            officialName="로쏘",
            bizNo="3058148738",
            unambiguous=True,
            evidences=[Evidence(source="비즈노", detail="상호명 검색 결과 1건")],
        )

        dumped = found.model_dump(by_alias=True)

        assert dumped["storeId"] == 1
        assert dumped["officialName"] == "로쏘"
        assert dumped["bizNo"] == "3058148738"
        assert dumped["failure"] is None

    def test_근거는_여러_건_담긴다(self):
        found = StoreFinding(
            storeId=1,
            evidences=[
                Evidence(source="비즈노", detail="상호명 일치"),
                Evidence(source="웹검색", detail="같은 주소", url="https://example.test"),
            ],
        )

        assert [e.source for e in found.evidences] == ["비즈노", "웹검색"]
        assert found.evidences[1].url == "https://example.test"


class TestEvidence:
    def test_출처가_없으면_거부한다(self):
        """출처 없는 근거는 사람이 검증할 수 없다."""
        with pytest.raises(ValidationError):
            Evidence(detail="어디서 왔는지 모르는 근거")

    def test_링크는_없어도_된다(self):
        """자체 데이터로 판단한 근거에는 외부 링크가 없다."""
        assert Evidence(source="국세청 대조", detail="폐업 상태").url is None


def test_응답은_요청_수와_성공_수를_함께_담는다():
    """부르는 쪽이 무엇이 빠졌는지 셀 수 있어야 한다."""
    response = InvestigationResponse(
        results=[StoreFinding(storeId=1), StoreFinding(storeId=2, failure="실패")],
        requested=2,
        succeeded=1,
    )

    assert response.requested == len(response.results)
    assert response.succeeded == 1
