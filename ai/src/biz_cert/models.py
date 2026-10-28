"""사업자등록증 읽기의 요청·응답 모양.

**응답 칸 이름은 백엔드 `BizCertExtractionResponse` 와 같다**(`bizNo`, `storeName` …). 백엔드가 그대로 옮겨 담고,
국세청 진위확인 결과(`ntsVerification`)만 백엔드가 붙인다. 못 읽은 칸은 빠지지 않고 `null` 이다.
"""

from __future__ import annotations

from datetime import date

from pydantic import BaseModel, ConfigDict, Field


class BizCertExtractionRequest(BaseModel):
    """백엔드가 보내는 파일. 백엔드가 업로드 때 이미 형식(내용으로 판별)과 크기를 검사했지만, 여기서도 한 번 더 본다."""

    # 검증 오류 문장에 입력값(파일 base64)을 싣지 않는다
    model_config = ConfigDict(extra="ignore", populate_by_name=True, hide_input_in_errors=True)

    content_base64: str = Field(alias="contentBase64", min_length=1)
    mime_type: str = Field(alias="mimeType")


class BizCertFields(BaseModel):
    """사업자등록증에서 읽은 값. 다듬은 뒤의 값이다 — 사업자번호는 숫자 10자리, 개업일은 날짜."""

    model_config = ConfigDict(populate_by_name=True)

    biz_no: str | None = Field(default=None, alias="bizNo")
    store_name: str | None = Field(default=None, alias="storeName")
    representative_name: str | None = Field(default=None, alias="representativeName")
    store_address: str | None = Field(default=None, alias="storeAddress")
    open_date: date | None = Field(default=None, alias="openDate")
    corp_name: str | None = Field(default=None, alias="corpName")
    business_type: str | None = Field(default=None, alias="businessType")
    business_item: str | None = Field(default=None, alias="businessItem")

    def filled_count(self) -> int:
        """읽은 칸 수. 로그에는 값 대신 이것만 남긴다 — 대표자 이름·주소가 들어 있다."""
        return sum(value is not None for value in self.model_dump().values())


class RawBizCertReading(BaseModel):
    """모델에게 받는 그대로의 모양 — 다듬기 전이다. Gemini 의 응답 스키마로도 쓴다.

    날짜·번호를 문자열로 받는 이유: 모델에게 형식까지 맞추게 하면 못 맞춘 값을 지어낼 수 있다.
    적힌 그대로 받아 우리가 다듬는다.
    """

    # 검증 오류 문장에 입력값(읽은 이름·주소)을 싣지 않는다
    model_config = ConfigDict(hide_input_in_errors=True)

    bizNo: str | None = None
    storeName: str | None = None
    representativeName: str | None = None
    storeAddress: str | None = None
    openDate: str | None = None
    corpName: str | None = None
    businessType: str | None = None
    businessItem: str | None = None
