"""조사 요청·결과 계약 — 엔드포인트를 거치지 않고 모델만 본다.

여기서 고정하는 건 **앞으로 올 조사 구현이 기대해도 되는 것**이다.
서버 테스트는 HTTP 경로를 보지만, 구현체는 이 모델을 직접 들고 쓴다.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from src.investigation import (
    InvestigationResponse,
    InvestigationTarget,
    StoreFinding,
)
from src.investigation.models import (
    ChangeField,
    PlaceCheck,
    PlaceStatus,
    Signal,
    SignalType,
    TaskClassification,
)


class TestInvestigationTarget:
    def test_백엔드_응답_필드명으로_만든다(self):
        """백엔드 `nts-checks` 응답 행을 그대로 넣을 수 있어야 한다."""
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
        found = StoreFinding(storeId=1, failure="조사 중 알 수 없는 오류가 났습니다")

        assert found.classification is None
        assert found.signals == []

    def test_응답은_백엔드_Task_Signal_칸만_담는다(self):
        """백엔드가 바꾸지 않고 저장할 수 있어야 한다 — 칸이 없는 값은 응답에서 뺀다."""
        found = StoreFinding(
            storeId=1,
            classification=TaskClassification.PRIORITY_CHECK,
            proposedChanges={"phone": "053-111-2222"},
            signals=[
                Signal(
                    signalType=SignalType.SIGNAL_HIGH,
                    evidenceText="전화번호: 053-111-2222 (출처 2곳)",
                    evidenceUrl="https://example.test",
                    field=ChangeField.PHONE,
                    observed="053-111-2222",
                    sourceCount=2,
                )
            ],
            mapCheck=PlaceCheck(status=PlaceStatus.FOUND, placeUrl="http://place.map.kakao.com/1"),
        )

        dumped = found.model_dump(by_alias=True)

        assert set(dumped) == {"storeId", "classification", "proposedChanges", "signals", "failure"}
        assert dumped["signals"] == [
            {
                "signalType": "SIGNAL_HIGH",
                "confidence": None,
                "evidenceText": "전화번호: 053-111-2222 (출처 2곳)",
                "evidenceUrl": "https://example.test",
                "field": "phone",
            }
        ]

    def test_응답에서_뺀_값도_규칙에서는_쓴다(self):
        signal = Signal(signalType=SignalType.SIGNAL_HIGH, evidenceText="t", field=ChangeField.PHONE, sourceCount=2)

        assert signal.field is ChangeField.PHONE and signal.source_count == 2


def test_응답은_요청_수와_성공_수를_함께_담는다():
    """부르는 쪽이 무엇이 빠졌는지 셀 수 있어야 한다."""
    response = InvestigationResponse(
        results=[StoreFinding(storeId=1), StoreFinding(storeId=2, failure="AI 응답이 제한 시간 안에 오지 않았습니다")],
        requested=2,
        succeeded=1,
    )

    assert response.requested == len(response.results)
    assert response.succeeded == 1
