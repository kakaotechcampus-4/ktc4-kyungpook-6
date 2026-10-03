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
    assert client.get("/health").json() == {"status": "ok"}


def test_backend_health_reports_unreachable(client):
    class Failing:
        def get_stores(self, *args, **kwargs):
            raise BackendError("connection refused")

    app.dependency_overrides[get_client] = lambda: Failing()

    response = client.get("/backend-health")

    # 본문만 unreachable 이고 200 이면 모니터링이 정상으로 집계한다. 상태 코드로도 알려야 한다.
    assert response.status_code == 503
    assert response.json()["status"] == "unreachable"
