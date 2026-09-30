"""간이 DB — 평가에서 백엔드 대신 하는 일(2차 조사 대상 선별)."""

from __future__ import annotations

import pytest

from eval.mock_db import is_second_round_target
from src.backend_client.models import StoreCheck


def _check(**overrides) -> StoreCheck:
    base = {
        "storeId": 1,
        "name": "예시분식",
        "internalStatus": "OPEN",
        "ntsLookup": "CONFIRMED",
        "statusComparison": "MATCH",
        "statusMismatch": False,
        "dataProblem": False,
    }
    return StoreCheck.model_validate(base | overrides)


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({}, True),
        ({"ntsLookup": "UNCONFIRMED", "statusComparison": "NOT_COMPARABLE"}, True),
        ({"statusComparison": "OPEN_BUT_CLOSED", "statusMismatch": True}, False),
        ({"ntsLookup": "NO_BIZ_NO", "statusComparison": "NOT_COMPARABLE", "dataProblem": True}, False),
    ],
    ids=["국세청과_일치", "국세청_조회_실패", "국세청이_변화를_잡음", "번호_없음"],
)
def test_2차_조사_대상은_국세청이_변화를_못_잡은_가게다(overrides, expected):
    assert is_second_round_target(_check(**overrides)) is expected
