"""FastAPI 표면 — 백엔드를 가짜로 끼워 넣고 응답 규약만 본다."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from src.backend_client import BackendError
from src.server.main import app, get_client


@pytest.fixture
def client():
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_health_does_not_touch_backend(client):
    # 백엔드를 아예 주입하지 않은 상태에서도 200이어야 한다.
    body = client.get("/health").json()

    assert body["status"] == "ok"
    # investigator 칸은 꽂힌 조사기 이름이다(값은 환경에 따라 다르다).
    # 칸 전체를 == 로 비교하면 칸이 늘 때마다 깨진다.
    assert "investigator" in body


def test_backend_health_reports_unreachable(client):
    class Failing:
        def get_nts_checks(self, *args, **kwargs):
            raise BackendError("connection refused")

    app.dependency_overrides[get_client] = lambda: Failing()

    response = client.get("/backend-health")

    # 본문만 unreachable 이고 200 이면 모니터링이 정상으로 집계한다. 상태 코드로도 알려야 한다.
    assert response.status_code == 503
    assert response.json()["status"] == "unreachable"


def test_backend_health_uses_the_only_endpoint_ai_may_call(client):
    """`/api/stores`를 부르면 안 된다.

    백엔드가 AI 서버 키에 열어 둔 조회는 `/api/stores/nts-checks` 하나뿐이다.
    권한 검사 스위치가 켜지면 `/api/stores`는 403이라, 연결이 멀쩡해도 영구 503이 된다.
    """
    called: list[str] = []

    class Recording:
        def get_stores(self, *args, **kwargs):
            called.append("get_stores")
            raise AssertionError("헬스체크가 /api/stores 를 부르면 안 된다")

        def get_nts_checks(self, *args, **kwargs):
            called.append("get_nts_checks")
            return None

    app.dependency_overrides[get_client] = lambda: Recording()

    response = client.get("/backend-health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    assert called == ["get_nts_checks"]
