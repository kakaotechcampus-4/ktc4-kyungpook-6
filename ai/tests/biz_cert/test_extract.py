"""사업자등록증 읽기 — 실제 Gemini 를 부르지 않고 가짜 클라이언트로 본다. 값은 모두 지어낸 것이다."""

from __future__ import annotations

import json
import logging
from datetime import date

import httpx
import pytest
from google.auth.exceptions import RefreshError
from google.genai import errors
from pydantic import ValidationError

from src.biz_cert.extract import (
    PROMPT,
    ExtractionFailed,
    ExtractorUnavailable,
    VertexBizCertExtractor,
    normalize,
    parse_reading,
)
from src.biz_cert.models import RawBizCertReading


class _FakeModels:
    def __init__(self, text: str | None = None, error: Exception | None = None) -> None:
        self.text = text
        self.error = error
        self.calls: list[dict] = []

    def generate_content(self, **kwargs):
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return type("Response", (), {"text": self.text})()


class _FakeClient:
    def __init__(self, models: _FakeModels) -> None:
        self.models = models


def _extractor(text: str | None = None, error: Exception | None = None) -> tuple[VertexBizCertExtractor, _FakeModels]:
    models = _FakeModels(text, error)
    return VertexBizCertExtractor(client=_FakeClient(models)), models


READING = {
    "bizNo": "123-45-67890",
    "storeName": "예시분식",
    "representativeName": "홍길동",
    "storeAddress": "가상특별시 예시구 샘플로 123",
    "openDate": "2019 년 03 월 02 일",
    "corpName": None,
    "businessType": "음식점업",
    "businessItem": "한식, 분식",
}


def test_reads_fields_and_normalizes_them():
    extractor, _ = _extractor(json.dumps(READING, ensure_ascii=False))

    fields = extractor.extract(b"%PDF-1.7 fake", "application/pdf")

    assert fields.biz_no == "1234567890"
    assert fields.open_date == date(2019, 3, 2)
    assert fields.store_name == "예시분식"
    assert fields.corp_name is None
    assert fields.business_item == "한식, 분식"


def test_sends_file_and_forces_json_shape_without_search_tool():
    """파일을 그대로 넘기고, 응답 모양을 스키마로 강제한다. 검색 도구를 붙이면 강제가 풀린다."""
    extractor, models = _extractor(json.dumps(READING, ensure_ascii=False))

    extractor.extract(b"\xff\xd8 fake jpeg", "image/jpeg")

    call = models.calls[0]
    part, prompt = call["contents"]
    assert part.inline_data.data == b"\xff\xd8 fake jpeg"
    assert part.inline_data.mime_type == "image/jpeg"
    assert prompt == PROMPT
    config = call["config"]
    assert config.response_mime_type == "application/json"
    assert config.response_schema is RawBizCertReading
    assert config.temperature == 0
    assert not config.tools
    assert config.http_options.timeout == 20_000


@pytest.mark.parametrize(
    "error",
    [httpx.ReadTimeout("slow"), httpx.ConnectError("refused"), errors.APIError(500, {"error": {"message": "boom"}})],
    ids=["timeout", "connect-error", "api-error"],
)
def test_model_failure_becomes_extraction_failed(error):
    extractor, _ = _extractor(error=error)

    with pytest.raises(ExtractionFailed) as raised:
        extractor.extract(b"%PDF-1.7 fake", "application/pdf")

    # 원인을 매달지 않는다 — 오류 추적기가 원인까지 찍어도 요청·응답 내용이 새지 않게
    assert raised.value.__cause__ is None and raised.value.__suppress_context__


def test_credential_problem_is_unavailable_not_failed():
    """자격증명이 만료·누락된 것은 모델이 틀린 게 아니다 — 502 가 아니라 503 쪽이다."""
    extractor, _ = _extractor(error=RefreshError("token expired"))

    with pytest.raises(ExtractorUnavailable):
        extractor.extract(b"%PDF-1.7 fake", "application/pdf")


@pytest.mark.parametrize("text", ["", "not json", "[1, 2]", '{"bizNo": 1234567890}'],
                         ids=["empty", "not-json", "not-object", "wrong-type"])
def test_response_out_of_shape_becomes_extraction_failed(text):
    with pytest.raises(ExtractionFailed):
        parse_reading(text)


def test_logs_only_how_many_fields_were_read(caplog):
    """대표자 이름·주소·사업자번호가 로그에 남지 않는다."""
    extractor, _ = _extractor(json.dumps(READING, ensure_ascii=False))

    with caplog.at_level(logging.DEBUG):
        extractor.extract(b"%PDF-1.7 fake", "application/pdf")

    logged = " ".join(record.getMessage() for record in caplog.records)
    assert "7/8" in logged
    for value in ("홍길동", "예시분식", "샘플로", "1234567890", "123-45-67890"):
        assert value not in logged


def test_failure_message_does_not_carry_the_response_body():
    """형식이 틀린 응답에도 읽은 값이 들어 있을 수 있다 — 예외 문장에 본문을 싣지 않는다."""
    with pytest.raises(ExtractionFailed) as raised:
        parse_reading('{"representativeName": ["홍길동"]}')

    assert "홍길동" not in str(raised.value)
    # 원인 예외(ValidationError·JSONDecodeError)에는 입력값·응답 전문이 들어 있어 매달지 않는다
    assert raised.value.__cause__ is None and raised.value.__suppress_context__


def test_validation_error_text_hides_input_values():
    """누가 검증 오류를 그대로 찍더라도 읽은 값이 문장에 실리지 않는다."""
    with pytest.raises(ValidationError) as raised:
        RawBizCertReading.model_validate({"representativeName": ["홍길동"]})

    assert "홍길동" not in str(raised.value)


class TestNormalize:
    def _one(self, **raw) -> dict:
        return normalize(RawBizCertReading(**raw)).model_dump()

    @pytest.mark.parametrize("value,expected", [
        ("123-45-67890", "1234567890"),
        ("123 45 67890", "1234567890"),
        ("1234567890", "1234567890"),
        ("123-45-6789", None),        # 한 자리 모자람 — 다른 사업자일 수 있어 버린다
        ("123-45-678901", None),
        ("없음", None),
        (None, None),
    ])
    def test_biz_no_keeps_only_ten_digits(self, value, expected):
        assert self._one(bizNo=value)["biz_no"] == expected

    @pytest.mark.parametrize("value,expected", [
        ("2019-03-02", date(2019, 3, 2)),
        ("2019.03.02", date(2019, 3, 2)),
        ("2019 년 03 월 02 일", date(2019, 3, 2)),
        ("2019년 3월 2일", date(2019, 3, 2)),
        ("2019-02-30", None),          # 없는 날짜
        ("이천십구년", None),
        (None, None),
    ])
    def test_open_date_becomes_a_date_or_nothing(self, value, expected):
        assert self._one(openDate=value)["open_date"] == expected

    @pytest.mark.parametrize("value", ["", "  ", "-", "없음", "null", "N/A"])
    def test_empty_marks_become_null(self, value):
        assert self._one(storeName=value)["store_name"] is None

    def test_squeezes_whitespace_but_keeps_the_text(self):
        assert self._one(storeAddress="  가상특별시   예시구\n샘플로 123 ")["store_address"] == "가상특별시 예시구 샘플로 123"
