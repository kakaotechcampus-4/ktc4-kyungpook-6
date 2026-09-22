"""FastAPI 표면 — 백엔드를 가짜로 끼워 넣고 응답 규약만 본다."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from src.backend_client import BackendError
from src.server.main import app, get_client
from tests.backend_client.test_client import NTS_CHECK_PAGE, client_returning


@pytest.fixture
def client():
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_health_does_not_touch_backend(client):
    # 백엔드를 아예 주입하지 않은 상태에서도 200이어야 한다.
    assert client.get("/health").json() == {"status": "ok"}


def test_investigation_targets_returns_page(client):
    app.dependency_overrides[get_client] = lambda: client_returning(NTS_CHECK_PAGE)

    body = client.get("/investigation-targets").json()

    # FastAPI 는 response_model 을 alias 로 직렬화한다. 즉 우리 응답 필드명이
    # 백엔드와 같은 캐멀케이스로 나간다 — 소비하는 쪽이 이름을 두 번 배우지 않아도 된다.
    assert body["totalElements"] == 1
    assert body["content"][0]["statusMismatch"] is True


def test_backend_failure_becomes_502(client):
    class Failing:
        def get_nts_checks(self, *args, **kwargs):
            raise BackendError("백엔드에 닿지 못했습니다")

    app.dependency_overrides[get_client] = lambda: Failing()

    response = client.get("/investigation-targets")

    assert response.status_code == 502
    assert "닿지 못했습니다" in response.json()["detail"]


def test_backend_health_reports_unreachable(client):
    class Failing:
        def get_stores(self, *args, **kwargs):
            raise BackendError("connection refused")

    app.dependency_overrides[get_client] = lambda: Failing()

    response = client.get("/backend-health")

    # 본문만 unreachable 이고 200 이면 모니터링이 정상으로 집계한다. 상태 코드로도 알려야 한다.
    assert response.status_code == 503
    assert response.json()["status"] == "unreachable"
