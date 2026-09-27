"""백엔드 클라이언트 — 네트워크 없이 요청 형태와 응답 파싱을 검증한다."""

from __future__ import annotations

import httpx
import pytest

from src.backend_client import BackendClient, BackendError, NtsCheckFilter

STORE_PAGE = {
    "content": [
        {
            "storeId": 1,
            "name": "예시분식",
            "addressRoad": "가상특별시 예시구 샘플로 123",
            "lat": 12.3456,
            "lng": 123.4567,
            "status": "OPEN",
            "category": "분식",
            "phone": "000-1234-5678",
            "bizNo": "1234567890",
            "lastCheckedAt": "2026-09-01T10:00:00",
        }
    ],
    "page": 0,
    "limit": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": False,
}

NTS_CHECK_PAGE = {
    "content": [
        {
            "storeId": 1,
            "name": "예시분식",
            "nameNormalized": "예시분식",
            "addressRoad": "가상특별시 예시구 샘플로 123",
            "addressNormalized": "가상특별시예시구샘플로123",
            "lat": 12.3456,
            "lng": 123.4567,
            "phone": "000-1234-5678",
            "bizNo": "1234567890",
            "internalStatus": "OPEN",
            "ntsLookup": "CONFIRMED",
            "ntsStatus": "CLOSED",
            "ntsClosedAt": "2026-03-01",
            "statusComparison": "OPEN_BUT_CLOSED",
            "statusMismatch": True,
            "dataProblem": False,
            "ntsCheckedAt": "2026-09-20T03:00:00",
        }
    ],
    "page": 0,
    "limit": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": False,
}


def client_returning(payload: dict, captured: list[httpx.Request] | None = None) -> BackendClient:
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        return httpx.Response(200, json=payload)

    transport = httpx.MockTransport(handler)
    return BackendClient(client=httpx.Client(transport=transport, base_url="http://backend"))


def test_get_stores_parses_page():
    page = client_returning(STORE_PAGE).get_stores()

    assert page.total_elements == 1
    assert page.has_next is False
    store = page.content[0]
    assert store.store_id == 1
    assert store.status.value == "OPEN"
    assert store.biz_no == "1234567890"


def test_get_nts_checks_parses_comparison_fields():
    page = client_returning(NTS_CHECK_PAGE).get_nts_checks(NtsCheckFilter.STATUS_MISMATCH)

    row = page.content[0]
    assert row.status_mismatch is True
    assert row.data_problem is False
    assert row.status_comparison.value == "OPEN_BUT_CLOSED"
    assert row.nts_lookup.value == "CONFIRMED"
    assert row.nts_closed_at.isoformat() == "2026-03-01"


def test_filter_is_sent_only_when_given():
    captured: list[httpx.Request] = []
    client_returning(NTS_CHECK_PAGE, captured).get_nts_checks(NtsCheckFilter.STATUS_MISMATCH)
    client_returning(NTS_CHECK_PAGE, captured).get_nts_checks()

    assert captured[0].url.params["filter"] == "STATUS_MISMATCH"
    # 빈 filter를 보내면 백엔드 enum 파싱이 400을 낸다. 아예 빼야 한다.
    assert "filter" not in captured[1].url.params


def test_error_status_becomes_backend_error():
    transport = httpx.MockTransport(lambda request: httpx.Response(500, text="boom"))
    client = BackendClient(client=httpx.Client(transport=transport, base_url="http://backend"))

    with pytest.raises(BackendError, match="500"):
        client.get_stores()


def test_unreachable_backend_becomes_backend_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = BackendClient(client=httpx.Client(transport=httpx.MockTransport(handler), base_url="http://backend"))

    with pytest.raises(BackendError, match="닿지 못했습니다"):
        client.get_stores()
