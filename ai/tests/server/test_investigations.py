"""POST /investigations — 백엔드가 AI를 부르는 자리."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from src.investigation import InvestigationTarget, InvestigatorUnavailable, StoreFinding
from src.investigation.mock import MockInvestigator
from src.investigation.web import WebInvestigator
from src.server import main
from src.server.main import MAX_TARGETS, app, get_investigator

TARGET = {"storeId": 1, "name": "성심당", "address": "대전 중구 은행동", "bizNo": None}


@pytest.fixture
def client():
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_GCP_프로젝트가_없으면_503(client, monkeypatch):
    # 자격증명이 없으면 UnavailableInvestigator — 못 한다는 걸 200으로 감추지 않는다.
    # 로컬 .env 에 값이 있으면 실제 Vertex 를 부르게 되므로 테스트에서 지운다.
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    response = client.post("/investigations", json=[TARGET])

    assert response.status_code == 503
    assert "연결되지 않았습니다" in response.json()["detail"]


def test_GCP_프로젝트가_있으면_웹검색_조사를_쓴다(monkeypatch):
    """PR #57 머지에서 이 연결이 빠져 설정이 있어도 항상 503 이었다 — 그 회귀를 막는다."""
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-project")
    monkeypatch.delenv("KAKAO_REST_API_KEY", raising=False)
    built = []
    monkeypatch.setattr(main, "VertexResearchProvider", lambda: built.append("vertex") or object())
    main._web_investigator.cache_clear()
    try:
        investigator = main.get_investigator()
    finally:
        main._web_investigator.cache_clear()

    assert isinstance(investigator, WebInvestigator)
    assert built == ["vertex"]


def test_알려진_케이스를_조사한다(client):
    app.dependency_overrides[get_investigator] = MockInvestigator

    body = client.post("/investigations", json=[TARGET]).json()

    assert body["requested"] == 1 and body["succeeded"] == 1
    found = body["results"][0]
    assert found["storeId"] == 1
    assert found["failure"] is None
    assert found["proposedChanges"] == {"name": "로쏘"}
    assert found["signals"][0]["confidence"] is None
    assert found["signals"][0]["field"] == "name"


def test_실패한_건도_결과에_남는다(client):
    app.dependency_overrides[get_investigator] = MockInvestigator

    body = client.post(
        "/investigations",
        json=[TARGET, {"storeId": 2, "name": "없는가게", "address": "어딘가"}],
    ).json()

    # 요청 수와 응답 수가 같아야 부르는 쪽이 무엇이 빠졌는지 알 수 있다.
    assert body["requested"] == 2 and len(body["results"]) == 2
    assert body["succeeded"] == 1
    assert body["results"][1]["failure"]["code"] == "ERROR"


def test_한_건의_예외가_배치를_죽이지_않는다(client):
    class Exploding:
        def investigate(self, target: InvestigationTarget) -> StoreFinding:
            if target.store_id == 1:
                raise ValueError("비즈노 응답이 깨졌습니다")
            return StoreFinding(storeId=target.store_id)

    app.dependency_overrides[get_investigator] = Exploding

    body = client.post(
        "/investigations",
        json=[TARGET, {"storeId": 2, "name": "정상가게"}],
    ).json()

    # 예외 문장은 응답에 나가지 않는다 — 종류와 고정 문장만.
    assert body["results"][0]["failure"] == {"code": "ERROR", "message": "조사 중 알 수 없는 오류가 났습니다"}
    assert body["results"][1]["failure"] is None
    assert body["succeeded"] == 1


@pytest.mark.parametrize(
    "payload,expected",
    [([], 422), ([TARGET] * (MAX_TARGETS + 1), 422), ([{"name": "이름만"}], 422)],
    ids=["빈_목록", "상한_초과", "storeId_누락"],
)
def test_잘못된_요청은_422(client, payload, expected):
    assert client.post("/investigations", json=payload).status_code == expected


def test_백엔드가_준_addressRoad_를_그대로_받는다(client):
    """백엔드 `nts-checks` 응답 행을 그대로 보내도 주소가 살아 있어야 한다.

    `address` 로만 받으면 `extra="ignore"` 에 먹혀 조용히 None 이 되고,
    주소 없이 상호명만으로 검색하게 된다.
    """
    captured: list[InvestigationTarget] = []

    class Capturing:
        def investigate(self, target: InvestigationTarget) -> StoreFinding:
            captured.append(target)
            return StoreFinding(storeId=target.store_id)

    app.dependency_overrides[get_investigator] = Capturing

    client.post(
        "/investigations",
        json=[{"storeId": 1, "name": "예시분식", "addressRoad": "대전 중구 은행동"}],
    )

    assert captured[0].address == "대전 중구 은행동"


def test_파이썬_필드명으로도_만들_수_있다():
    """조사 구현이 StoreCheck 를 받아 파이썬 필드명으로 조립할 수 있어야 한다."""
    target = InvestigationTarget(store_id=1, name="예시분식", address="대전 중구 은행동")

    assert target.store_id == 1


def test_중간에_구현이_끊겨도_이미_끝낸_결과는_돌려준다(client):
    """100건 중 99건을 처리한 뒤 자격증명이 만료돼도 그 99건을 버리지 않는다."""

    class DiesAfterFirst:
        def __init__(self) -> None:
            self.calls = 0

        def investigate(self, target: InvestigationTarget) -> StoreFinding:
            self.calls += 1
            if self.calls > 1:
                raise InvestigatorUnavailable("자격증명이 만료됐습니다")
            return StoreFinding(storeId=target.store_id)

    app.dependency_overrides[get_investigator] = DiesAfterFirst

    response = client.post(
        "/investigations",
        json=[TARGET, {"storeId": 2, "name": "둘째"}, {"storeId": 3, "name": "셋째"}],
    )

    assert response.status_code == 200
    body = response.json()
    assert body["requested"] == 3 and len(body["results"]) == 3
    assert body["results"][0]["failure"] is None
    assert body["results"][1]["failure"]["code"] == "UNAVAILABLE"
    assert body["results"][2]["failure"]["code"] == "UNAVAILABLE"


class Test조사기_선택:
    """`INVESTIGATOR` 로 어떤 구현이 꽂히는가.

    `get_investigator()` 가 지금까지 항상 `UnavailableInvestigator` 를 돌려줘서,
    백엔드가 불러도 무조건 503 이었다. 환경변수로 고르게 바꾸면서 붙인 테스트다.
    """

    @staticmethod
    def _mode(monkeypatch, value: str | None, *, gcp: str | None = None) -> str:
        from src.server import main

        main._web_investigator.cache_clear()
        if value is None:
            monkeypatch.delenv("INVESTIGATOR", raising=False)
        else:
            monkeypatch.setenv("INVESTIGATOR", value)
        if gcp is None:
            monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
        else:
            monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", gcp)
        return main.investigator_mode()

    def test_off_이면_항상_못_한다고_답한다(self, monkeypatch):
        # 자격증명이 있어도 off 가 이긴다 — 붙이기 전 상태를 일부러 유지할 수 있어야 한다.
        assert self._mode(monkeypatch, "off", gcp="some-project") == "UnavailableInvestigator"

    def test_mock_이면_가짜_조사기(self, monkeypatch):
        assert self._mode(monkeypatch, "mock") == "MockInvestigator"

    def test_값이_없고_자격증명도_없으면_못_한다고_답한다(self, monkeypatch):
        assert self._mode(monkeypatch, None) == "UnavailableInvestigator"

    def test_대소문자와_공백을_가리지_않는다(self, monkeypatch):
        assert self._mode(monkeypatch, "  MOCK  ") == "MockInvestigator"

    def test_web_인데_자격증명이_깨졌으면_500_이_아니라_503(self, monkeypatch):
        # 조사기를 만들다 터지면 서버가 죽는 게 아니라 "못 한다"로 떨어져야 한다.
        from src.server import main

        main._web_investigator.cache_clear()
        monkeypatch.setenv("INVESTIGATOR", "web")
        monkeypatch.setattr(
            main, "_web_investigator", lambda: (_ for _ in ()).throw(RuntimeError("자격증명 없음"))
        )
        assert main.investigator_mode() == "UnavailableInvestigator"


def test_health_가_꽂힌_조사기를_알려준다(client, monkeypatch):
    # 503 이 날 때 "자격증명이 없어서"인지 "코드가 안 꽂혀서"인지를
    # 배포 환경에서 확인할 길이 이것뿐이다.
    monkeypatch.setenv("INVESTIGATOR", "mock")
    body = client.get("/health").json()

    assert body["status"] == "ok"
    assert body["investigator"] == "MockInvestigator"


def test_목업_조사기로_연동_흐름이_끝까지_통과한다(client, monkeypatch):
    """백엔드 `GET /api/stores/nts-checks` 응답 행을 **그대로** 던져도 받아야 한다.

    `ntsLookup`·`statusComparison` 처럼 조사에 쓰지 않는 칸이 섞여 있는데,
    여기서 422 가 나면 백엔드가 행을 골라 담아야 한다 — 그러면 계약이 두 곳으로 갈린다.
    """
    monkeypatch.setenv("INVESTIGATOR", "mock")
    row = {
        "storeId": 1,
        "name": "성심당",
        "nameNormalized": "성심당",
        "addressRoad": "대전 중구 은행동",
        "addressNormalized": "대전중구은행동",
        "lat": 36.3,
        "lng": 127.4,
        "category": "한식",
        "bizNo": "000-00-00000",
        "internalStatus": "UNKNOWN",
        "ntsLookup": "CONFIRMED",
        "ntsStatus": "ACTIVE",
        "statusComparison": "NOT_COMPARABLE",
        "statusMismatch": False,
        "dataProblem": False,
    }
    response = client.post("/investigations", json=[row])

    assert response.status_code == 200
    body = response.json()
    assert body["requested"] == 1
    assert len(body["results"]) == 1
    assert body["results"][0]["storeId"] == 1


class Test수신_인증:
    """조사를 시작할 수 있는 건 백엔드뿐이다.

    `/investigations` 는 Vertex·카카오 쿼터를 쓰는 호출이라, 아무나 부를 수 있으면
    돈과 한도가 샌다. 문서에 "남은 구멍"으로 적혀 있던 것이다.
    """

    def test_키가_없으면_검사하지_않는다(self, client, monkeypatch):
        # 로컬에서 띄워 보는 길을 막지 않는다. compose 에서 포트를 안 열어 두어
        # 같은 도커 네트워크 안에서만 닿는다.
        monkeypatch.delenv("INTERNAL_API_KEY", raising=False)
        monkeypatch.setenv("INVESTIGATOR", "mock")

        assert client.post("/investigations", json=[TARGET]).status_code == 200

    def test_키가_있는데_헤더가_없으면_401(self, client, monkeypatch):
        monkeypatch.setenv("INTERNAL_API_KEY", "sekret")
        monkeypatch.setenv("INVESTIGATOR", "mock")

        response = client.post("/investigations", json=[TARGET])

        assert response.status_code == 401
        assert "X-API-KEY" in response.json()["detail"]

    def test_키가_틀리면_401(self, client, monkeypatch):
        monkeypatch.setenv("INTERNAL_API_KEY", "sekret")
        monkeypatch.setenv("INVESTIGATOR", "mock")

        response = client.post("/investigations", json=[TARGET], headers={"X-API-KEY": "nope"})

        assert response.status_code == 401

    def test_키가_맞으면_통과한다(self, client, monkeypatch):
        monkeypatch.setenv("INTERNAL_API_KEY", "sekret")
        monkeypatch.setenv("INVESTIGATOR", "mock")

        response = client.post("/investigations", json=[TARGET], headers={"X-API-KEY": "sekret"})

        assert response.status_code == 200

    def test_health_는_키가_없어도_열려_있다(self, client, monkeypatch):
        # 컨테이너 프로브가 키를 들고 있을 이유가 없다.
        monkeypatch.setenv("INTERNAL_API_KEY", "sekret")

        assert client.get("/health").status_code == 200
