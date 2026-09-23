"""테스트·로컬 확인용 조사 구현.

에이전트 1차 조사 구현은 아직 없다. 이 구현은 **엔드포인트가 제대로 엮였는지**
확인하는 용도다. 알려진 케이스만 답하고 나머지는 실패로 둔다.
"""

from __future__ import annotations

from src.investigation.models import Evidence, InvestigationTarget, StoreFinding

#: 상호명이 정식 등록 상호명과 다른, 실제로 확인된 사례.
_KNOWN = {
    "성심당": ("로쏘", "3058148738"),
}


class MockInvestigator:
    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        found = _KNOWN.get(target.name)
        if not found:
            return StoreFinding(storeId=target.store_id, failure="후보를 찾지 못했습니다")

        official_name, biz_no = found
        return StoreFinding(
            storeId=target.store_id,
            officialName=official_name,
            bizNo=biz_no,
            unambiguous=True,
            evidences=[
                Evidence(
                    source="mock",
                    detail=f"'{target.name}' 의 정식 등록 상호명은 '{official_name}' 로 알려져 있다",
                )
            ],
        )
