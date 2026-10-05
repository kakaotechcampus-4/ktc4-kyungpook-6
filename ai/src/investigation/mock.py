"""테스트·로컬 확인용 조사 구현.

이 구현은 **엔드포인트가 제대로 엮였는지** 확인하는 용도다. 알려진 케이스만 답하고 나머지는 실패로 둔다.
"""

from __future__ import annotations

from src.investigation.models import (
    ChangeField,
    InvestigationTarget,
    Signal,
    SignalType,
    StoreFinding,
    TaskClassification,
)

#: 조사하면 변화가 나오는, 실제로 확인된 사례. 상호명 → (항목, 새 값, 근거)
_KNOWN = {
    "성심당": (ChangeField.NAME, "로쏘", "정식 등록 상호명이 '로쏘' 로 확인된다"),
}


class MockInvestigator:
    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        known = _KNOWN.get(target.name)
        if not known:
            return StoreFinding(storeId=target.store_id, failure="근거를 찾지 못했습니다")

        change_field, value, evidence = known
        return StoreFinding(
            storeId=target.store_id,
            classification=TaskClassification.PRIORITY_CHECK,
            proposedChanges={change_field.value: value},
            signals=[Signal(signalType=SignalType.SIGNAL_HIGH, evidenceText=evidence, field=change_field, observed=value)],
        )
