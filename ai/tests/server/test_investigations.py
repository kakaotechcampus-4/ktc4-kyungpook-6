"""POST /investigations — 백엔드가 AI를 부르는 자리."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from src.investigation import InvestigationTarget, StoreFinding
from src.investigation.mock import MockInvestigator
from src.server.main import MAX_TARGETS, app, get_investigator

TARGET = {"storeId": 1, "name": "성심당", "address": "대전 중구 은행동", "bizNo": None}


@pytest.fixture
def client():
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_구현이_없으면_503(client):
    # 기본 구현은 UnavailableInvestigator — 아직 못 한다는 걸 200으로 감추지 않는다.
    response = client.post("/investigations", json=[TARGET])

    assert response.status_code == 503
    assert "PROMPT-69" in response.json()["detail"]


def test_알려진_케이스를_조사한다(client):
    app.dependency_overrides[get_investigator] = MockInvestigator

    body = client.post("/investigations", json=[TARGET]).json()

    assert body["requested"] == 1 and body["succeeded"] == 1
    found = body["results"][0]
    assert found["storeId"] == 1
    assert found["officialName"] == "로쏘"
    assert found["unambiguous"] is True
    assert found["evidences"][0]["source"] == "mock"


def test_실패한_건도_결과에_남는다(client):
    app.dependency_overrides[get_investigator] = MockInvestigator

    body = client.post(
        "/investigations",
        json=[TARGET, {"storeId": 2, "name": "없는가게", "address": "어딘가"}],
    ).json()

    # 요청 수와 응답 수가 같아야 부르는 쪽이 무엇이 빠졌는지 알 수 있다.
    assert body["requested"] == 2 and len(body["results"]) == 2
    assert body["succeeded"] == 1
    assert body["results"][1]["failure"] == "후보를 찾지 못했습니다"


def test_한_건의_예외가_배치를_죽이지_않는다(client):
    class Exploding:
        def investigate(self, target: InvestigationTarget) -> StoreFinding:
            if target.store_id == 1:
                raise ValueError("비즈노 응답이 깨졌습니다")
            return StoreFinding(storeId=target.store_id, officialName="정상가게")

    app.dependency_overrides[get_investigator] = Exploding

    body = client.post(
        "/investigations",
        json=[TARGET, {"storeId": 2, "name": "정상가게"}],
    ).json()

    assert body["results"][0]["failure"] == "비즈노 응답이 깨졌습니다"
    assert body["results"][1]["officialName"] == "정상가게"
    assert body["succeeded"] == 1


@pytest.mark.parametrize(
    "payload,expected",
    [([], 422), ([TARGET] * (MAX_TARGETS + 1), 422), ([{"name": "이름만"}], 422)],
    ids=["빈_목록", "상한_초과", "storeId_누락"],
)
def test_잘못된_요청은_422(client, payload, expected):
    assert client.post("/investigations", json=payload).status_code == expected
