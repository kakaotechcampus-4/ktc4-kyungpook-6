"""`POST /biz-cert-extractions` — 읽기 구현을 가짜로 끼워 넣고 응답 규약만 본다. 값은 모두 지어낸 것이다."""

from __future__ import annotations

import base64
import logging
from datetime import date

import pytest
from fastapi.testclient import TestClient

from src.biz_cert import (
    MAX_BYTES,
    BizCertFields,
    ExtractionFailed,
    ExtractorUnavailable,
    MockBizCertExtractor,
    UnavailableExtractor,
)
from src.server.main import (
    MAX_BIZ_CERT_REQUEST_BYTES,
    _mock_biz_cert_extractor,
    app,
    get_biz_cert_extractor,
)

URL = "/biz-cert-extractions"
PDF = base64.b64encode(b"%PDF-1.7 fake").decode()


class _Recording:
    def __init__(self, result: BizCertFields | None = None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error
        self.calls: list[tuple[bytes, str]] = []

    def extract(self, data: bytes, mime_type: str) -> BizCertFields:
        self.calls.append((data, mime_type))
        if self.error is not None:
            raise self.error
        return self.result


@pytest.fixture
def client(monkeypatch):
    monkeypatch.delenv("INTERNAL_API_KEY", raising=False)
    yield TestClient(app)
    app.dependency_overrides.clear()


def _use(extractor) -> None:
    app.dependency_overrides[get_biz_cert_extractor] = lambda: extractor


def test_returns_fields_in_backend_shape_with_nulls(client):
    """칸 이름은 백엔드 응답과 같고, 못 읽은 칸도 빠지지 않고 null 이다."""
    extractor = _Recording(BizCertFields(biz_no="1234567890", open_date=date(2019, 3, 2)))
    _use(extractor)

    response = client.post(URL, json={"contentBase64": PDF, "mimeType": "application/pdf"})

    assert response.status_code == 200
    assert response.json() == {
        "bizNo": "1234567890", "storeName": None, "representativeName": None, "storeAddress": None,
        "openDate": "2019-03-02", "corpName": None, "businessType": None, "businessItem": None,
    }
    assert extractor.calls == [(b"%PDF-1.7 fake", "application/pdf")]


def test_requires_backend_key_when_configured(client, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-internal-key")
    extractor = _Recording(BizCertFields())
    _use(extractor)
    body = {"contentBase64": PDF, "mimeType": "application/pdf"}

    assert client.post(URL, json=body).status_code == 401
    assert client.post(URL, json=body, headers={"X-API-KEY": "wrong"}).status_code == 401
    assert client.post(URL, json=body, headers={"X-API-KEY": "test-internal-key"}).status_code == 200
    assert len(extractor.calls) == 1   # 키가 틀린 요청은 모델까지 가지 않는다


@pytest.mark.parametrize("body,expected", [
    ({"contentBase64": PDF, "mimeType": "image/webp"}, 415),
    ({"contentBase64": PDF, "mimeType": "text/html"}, 415),
    ({"contentBase64": "@@not base64@@", "mimeType": "application/pdf"}, 422),
    ({"contentBase64": "", "mimeType": "application/pdf"}, 422),
    ({"mimeType": "application/pdf"}, 422),
], ids=["webp", "html", "not-base64", "empty", "missing"])
def test_rejects_what_it_should_not_read_before_calling_the_model(client, body, expected):
    extractor = _Recording(BizCertFields())
    _use(extractor)

    assert client.post(URL, json=body).status_code == expected
    assert extractor.calls == []


def test_rejects_oversized_body_before_reading_it(client, monkeypatch):
    """본문을 읽기 전에 Content-Length 로 거른다 — 키 검사보다 본문 파싱이 먼저라, 키 없는 요청도 막아야 한다."""
    monkeypatch.setenv("INTERNAL_API_KEY", "test-internal-key")
    extractor = _Recording(BizCertFields())
    _use(extractor)
    headers = {"Content-Type": "application/json", "Content-Length": str(MAX_BIZ_CERT_REQUEST_BYTES + 1)}

    response = client.post(URL, content=b"{}", headers=headers)

    assert response.status_code == 413   # 키가 없어도 401 보다 먼저 — 본문을 열지 않았다
    assert extractor.calls == []


def test_rejects_body_without_length(client):
    """길이를 밝히지 않은 본문(chunked)은 크기를 미리 알 수 없어 받지 않는다."""
    _use(_Recording(BizCertFields()))

    def chunks():
        yield b'{"contentBase64": "' + PDF.encode() + b'", "mimeType": "application/pdf"}'

    assert client.post(URL, content=chunks(), headers={"Content-Type": "application/json"}).status_code == 411


def test_wrong_key_does_not_build_the_extractor(client, monkeypatch):
    """키 검사가 먼저다 — 키가 틀린 요청에 읽기 구현(Vertex 클라이언트)을 만들지 않는다."""
    monkeypatch.setenv("INTERNAL_API_KEY", "test-internal-key")
    built = []
    app.dependency_overrides[get_biz_cert_extractor] = lambda: built.append(1) or _Recording(BizCertFields())

    assert client.post(URL, json={"contentBase64": PDF, "mimeType": "application/pdf"}).status_code == 401
    assert built == []


def test_rejects_files_over_the_upload_limit(client):
    extractor = _Recording(BizCertFields())
    _use(extractor)
    too_big = base64.b64encode(b"\0" * (MAX_BYTES + 1)).decode()

    assert client.post(URL, json={"contentBase64": too_big, "mimeType": "application/pdf"}).status_code == 413
    assert extractor.calls == []


@pytest.mark.parametrize("error,expected", [
    (ExtractorUnavailable("no credentials"), 503),
    (ExtractionFailed("timeout"), 502),
], ids=["unavailable", "failed"])
def test_failures_map_to_status_without_leaking_detail(client, error, expected):
    _use(_Recording(error=error))

    response = client.post(URL, json={"contentBase64": PDF, "mimeType": "application/pdf"})

    assert response.status_code == expected
    assert "credentials" not in response.text and "timeout" not in response.text


def test_off_mode_is_503_and_mock_mode_returns_fake_values(monkeypatch):
    monkeypatch.setenv("BIZ_CERT_EXTRACTOR", "off")
    assert isinstance(get_biz_cert_extractor(), UnavailableExtractor)

    monkeypatch.setenv("BIZ_CERT_EXTRACTOR", "mock")
    assert isinstance(get_biz_cert_extractor(), MockBizCertExtractor)


def test_mock_mode_warns_once(monkeypatch, caplog):
    """mock 이 운영에 잘못 켜지면 알아채게 처음 한 번 경고한다 — 요청마다 남기면 로그가 쌓인다."""
    monkeypatch.setenv("BIZ_CERT_EXTRACTOR", "mock")
    _mock_biz_cert_extractor.cache_clear()

    with caplog.at_level(logging.WARNING):
        get_biz_cert_extractor()
        get_biz_cert_extractor()

    assert sum("mock" in record.getMessage() for record in caplog.records) == 1


def test_without_gcp_project_it_is_unavailable(monkeypatch):
    monkeypatch.delenv("BIZ_CERT_EXTRACTOR", raising=False)
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)

    assert isinstance(get_biz_cert_extractor(), UnavailableExtractor)
