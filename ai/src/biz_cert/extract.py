"""사업자등록증을 읽어 가입 form 에 채울 값을 돌려준다.

백엔드가 `POST /biz-cert-extractions` 로 파일을 보낸다. 사진(JPEG·PNG)이든 PDF 든 Gemini 에 그대로 넘기고,
칸을 정한 JSON 으로 받는다(응답 스키마 강제). 받은 값은 적힌 그대로라, 사업자번호·개업일은 여기서 다듬는다.

**값을 지어내지 않게 한다.** 흐리거나 없는 칸은 비우라고 지시하고, 다듬을 수 없는 값(자릿수가 틀린 번호,
날짜가 아닌 개업일)은 버린다. 틀린 값이 칸에 들어가는 것보다 점주가 직접 적는 편이 낫다.

**읽은 값은 남기지 않는다.** 로그에는 몇 칸을 읽었는지만 적는다 — 대표자 이름·주소가 들어 있다.
"""

from __future__ import annotations

import json
import logging
import os
import re
from datetime import date
from typing import Protocol

import httpx
from pydantic import ValidationError

from src.biz_cert.models import BizCertFields, RawBizCertReading

logger = logging.getLogger(__name__)

#: 받는 형식. 백엔드 업로드가 사업자등록증으로 받는 형식과 같다(PDF·JPEG·PNG).
ALLOWED_MIME_TYPES = frozenset({"application/pdf", "image/jpeg", "image/png"})
#: 받는 크기. 백엔드 업로드 상한(사업자등록증 PDF 10MB)과 같다.
MAX_BYTES = 10 * 1024 * 1024
#: 모델 응답을 기다리는 시간. 가입 화면이 기다리는 동안이라 길게 두지 않는다 — 넘기면 점주가 직접 적는다.
LLM_TIMEOUT_SECONDS = 20.0

PROMPT = """\
이 파일은 한국 사업자등록증이다. 아래 칸을 읽어 JSON 으로만 답해라.

- bizNo: 등록번호 (예: 123-45-67890)
- storeName: 상호. 법인 등록증에 상호가 따로 없으면 null
- representativeName: 성명 또는 대표자
- storeAddress: 사업장 소재지 (법인의 본점 소재지가 아니다)
- openDate: 개업연월일 (적힌 그대로)
- corpName: 법인명(단체명). 개인사업자 등록증이면 null
- businessType: 업태. 여러 개면 쉼표로 잇는다
- businessItem: 종목. 여러 개면 쉼표로 잇는다

규칙:
- 적힌 글자를 그대로 옮긴다. 고치거나 짐작해서 채우지 않는다.
- 흐리거나 가려져 읽을 수 없는 칸, 등록증에 없는 칸은 null 로 둔다.
- 주민등록번호·법인등록번호·생년월일은 옮기지 않는다.
- 사업자등록증이 아니면 모든 칸을 null 로 둔다.
"""

_BIZ_NO_DIGITS = 10
_DATE = re.compile(r"(\d{4})\D{1,3}(\d{1,2})\D{1,3}(\d{1,2})")
_EMPTY_MARKS = frozenset({"", "-", "없음", "null", "none", "n/a"})


class ExtractorUnavailable(Exception):
    """읽기 구현을 쓸 수 없다 — 자격증명이 없거나 일부러 꺼 둠. 요청 전체가 503 이다."""


class ExtractionFailed(Exception):
    """모델을 불렀지만 읽지 못했다 — 시간 초과, 모델 오류, 형식에 맞지 않는 응답."""


class BizCertExtractor(Protocol):
    def extract(self, data: bytes, mime_type: str) -> BizCertFields: ...


class UnavailableExtractor:
    """붙이기 전 상태. 항상 `ExtractorUnavailable`."""

    def extract(self, data: bytes, mime_type: str) -> BizCertFields:
        raise ExtractorUnavailable("사업자등록증 읽기를 쓸 수 없습니다 (GOOGLE_CLOUD_PROJECT 또는 BIZ_CERT_EXTRACTOR 확인)")


class MockBizCertExtractor:
    """외부 호출 없이 정해진 가짜 값을 준다. 백엔드·앱과 **연동 흐름만** 확인할 때 쓴다. 값은 실제가 아니다."""

    def extract(self, data: bytes, mime_type: str) -> BizCertFields:
        return BizCertFields(
            biz_no="1234567890",
            store_name="예시분식",
            representative_name="홍길동",
            store_address="가상특별시 예시구 샘플로 123",
            open_date=date(2019, 3, 2),
            corp_name=None,
            business_type="음식점업",
            business_item="한식, 분식",
        )


class VertexBizCertExtractor:
    """Vertex Gemini 로 읽는다. ADC 로 인증한다(조사와 같은 자격증명)."""

    def __init__(
        self,
        model: str = "gemini-2.5-flash",
        *,
        client: object | None = None,
        timeout_seconds: float = LLM_TIMEOUT_SECONDS,
    ) -> None:
        self._model = model
        self._client = client if client is not None else self._build_client()
        self._timeout_seconds = timeout_seconds

    @staticmethod
    def _build_client() -> object:
        from google import genai

        return genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )

    def extract(self, data: bytes, mime_type: str) -> BizCertFields:
        from google.auth import exceptions as auth_errors
        from google.genai import errors, types

        # 원인 예외는 매달지 않는다(from None) — 요청·응답 내용이 원인 쪽에 실려 있을 수 있다.
        # 나중에 로그·오류 추적기가 원인까지 찍어도 읽은 값이 새지 않게 한다.
        try:
            response = self._client.models.generate_content(
                model=self._model,
                contents=[types.Part.from_bytes(data=data, mime_type=mime_type), PROMPT],
                config=types.GenerateContentConfig(
                    # 검색 도구를 쓰지 않아 형식을 강제할 수 있다 — 칸이 빠지거나 설명 문장이 섞이지 않는다
                    response_mime_type="application/json",
                    response_schema=RawBizCertReading,
                    temperature=0,
                    http_options=types.HttpOptions(timeout=int(self._timeout_seconds * 1000)),
                ),
            )
        except httpx.TimeoutException:
            raise ExtractionFailed(f"모델 응답이 {self._timeout_seconds:.0f}초 안에 오지 않았습니다") from None
        except httpx.HTTPError as e:
            raise ExtractionFailed(f"모델에 닿지 못했습니다: {type(e).__name__}") from None
        except errors.APIError as e:
            raise ExtractionFailed(f"모델 호출 실패: {e.code}") from None
        except auth_errors.GoogleAuthError as e:
            # 자격증명이 만료되거나 빠졌다 — 모델이 틀린 게 아니라 지금 읽을 수 없는 것이다(503)
            raise ExtractorUnavailable(f"자격증명 문제: {type(e).__name__}") from None

        fields = normalize(parse_reading(response.text or ""))
        logger.info("사업자등록증 읽기 - 읽은 칸 %s/8", fields.filled_count())
        return fields


def parse_reading(text: str) -> RawBizCertReading:
    """모델 응답(JSON)을 그대로의 모양으로 읽는다. 형식이 틀리면 `ExtractionFailed`."""
    try:
        return RawBizCertReading.model_validate(json.loads(text))
    except (json.JSONDecodeError, ValidationError) as e:
        # 응답 본문은 남기지 않는다 — 읽은 개인정보가 들어 있을 수 있다.
        # 원인도 매달지 않는다: JSONDecodeError.doc 에 응답 전문이, ValidationError 에 입력값이 들어 있다
        raise ExtractionFailed(f"모델 응답이 정한 모양이 아닙니다: {type(e).__name__}") from None


def normalize(raw: RawBizCertReading) -> BizCertFields:
    """적힌 그대로의 값을 다듬는다. 다듬을 수 없는 값은 버린다(지어내지 않는다)."""
    return BizCertFields(
        biz_no=_biz_no(raw.bizNo),
        store_name=_text(raw.storeName),
        representative_name=_text(raw.representativeName),
        store_address=_text(raw.storeAddress),
        open_date=_open_date(raw.openDate),
        corp_name=_text(raw.corpName),
        business_type=_text(raw.businessType),
        business_item=_text(raw.businessItem),
    )


def _text(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = " ".join(value.split())
    return None if cleaned.lower() in _EMPTY_MARKS else cleaned


def _biz_no(value: str | None) -> str | None:
    """숫자만 남긴다. 10자리가 아니면 버린다 — 한 자리라도 틀린 번호는 다른 사업자다."""
    if value is None:
        return None
    digits = re.sub(r"\D", "", value)
    return digits if len(digits) == _BIZ_NO_DIGITS else None


def _open_date(value: str | None) -> date | None:
    """`2019-03-02`·`2019.03.02`·`2019 년 03 월 02 일` 을 날짜로. 날짜가 아니면 버린다."""
    if value is None:
        return None
    match = _DATE.search(value)
    if match is None:
        return None
    try:
        return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    except ValueError:
        return None
