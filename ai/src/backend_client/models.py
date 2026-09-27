"""백엔드 응답을 그대로 옮긴 모델.

**백엔드가 단일 기준이다.** 여기 있는 enum·필드는 전부 백엔드 Java 정의를 손으로 옮긴 것이라
백엔드가 바뀌면 조용히 어긋난다. 5주차(가게 상태)·6주차(Task 분류)에 실제로 두 번 어긋났고,
그때마다 "각자 합리적으로 작업했는데 병렬로 진행하다 보니" 갈라졌다.

그래서 `tests/backend_client/test_contract.py`가 **백엔드 Java 파일을 직접 읽어** 아래 enum과
대조한다. 같은 리포에 있어서 가능한 방식이다. 백엔드가 값을 바꾸면 테스트가 깨진다.
"""

from __future__ import annotations

from datetime import date, datetime
from enum import Enum
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field


class StoreStatus(str, Enum):
    """우리 DB가 보는 가게 영업 상태. 국세청 상태(`BusinessState`)와 뜻이 다르다."""

    OPEN = "OPEN"
    SUSPENDED = "SUSPENDED"
    CLOSED = "CLOSED"
    UNKNOWN = "UNKNOWN"


class BusinessState(str, Enum):
    """국세청이 사업자등록번호를 보고 판단한 상태."""

    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"
    CLOSED = "CLOSED"
    NOT_REGISTERED = "NOT_REGISTERED"


class NtsLookupResult(str, Enum):
    """국세청 칸이 비어 있는 이유. `UNCONFIRMED`는 재조회하면 채워질 수 있다."""

    CONFIRMED = "CONFIRMED"
    UNCONFIRMED = "UNCONFIRMED"
    NO_BIZ_NO = "NO_BIZ_NO"


class StatusComparison(str, Enum):
    """우리 상태와 국세청 상태를 백엔드가 비교한 결과. 해석은 AI 몫이다."""

    MATCH = "MATCH"
    OPEN_BUT_SUSPENDED = "OPEN_BUT_SUSPENDED"
    OPEN_BUT_CLOSED = "OPEN_BUT_CLOSED"
    SUSPENDED_BUT_ACTIVE = "SUSPENDED_BUT_ACTIVE"
    SUSPENDED_BUT_CLOSED = "SUSPENDED_BUT_CLOSED"
    CLOSED_BUT_ACTIVE = "CLOSED_BUT_ACTIVE"
    CLOSED_BUT_SUSPENDED = "CLOSED_BUT_SUSPENDED"
    NTS_NOT_REGISTERED = "NTS_NOT_REGISTERED"
    NOT_COMPARABLE = "NOT_COMPARABLE"


class NtsCheckFilter(str, Enum):
    """조회 대상. 둘은 **대응 주체가 다르다** — 섞어서 조사에 넘기면 안 된다."""

    STATUS_MISMATCH = "STATUS_MISMATCH"  # AI 조사 대상
    DATA_PROBLEM = "DATA_PROBLEM"  # 사람이 데이터를 고칠 대상


class Store(BaseModel):
    """`GET /api/stores` 한 건. 화면용이라 사업자번호 외 국세청 정보는 없다."""

    model_config = ConfigDict(extra="ignore")

    store_id: int = Field(alias="storeId")
    name: str
    address_road: str | None = Field(default=None, alias="addressRoad")
    lat: float | None = None
    lng: float | None = None
    status: StoreStatus
    category: str | None = None
    phone: str | None = None
    biz_no: str | None = Field(default=None, alias="bizNo")
    last_checked_at: datetime | None = Field(default=None, alias="lastCheckedAt")


class StoreCheck(BaseModel):
    """`GET /api/stores/nts-checks` 한 건 — AI 1차 조사에 넘길 자료.

    `ntsStatus`가 비어 있으면 `ntsLookup`이 그 이유를 말해 준다. 두 값을 같이 봐야 한다.
    """

    model_config = ConfigDict(extra="ignore")

    store_id: int = Field(alias="storeId")
    name: str
    name_normalized: str | None = Field(default=None, alias="nameNormalized")
    address_road: str | None = Field(default=None, alias="addressRoad")
    address_normalized: str | None = Field(default=None, alias="addressNormalized")
    lat: float | None = None
    lng: float | None = None
    phone: str | None = None
    biz_no: str | None = Field(default=None, alias="bizNo")
    internal_status: StoreStatus = Field(alias="internalStatus")
    nts_lookup: NtsLookupResult = Field(alias="ntsLookup")
    nts_status: BusinessState | None = Field(default=None, alias="ntsStatus")
    nts_closed_at: date | None = Field(default=None, alias="ntsClosedAt")
    status_comparison: StatusComparison = Field(alias="statusComparison")
    status_mismatch: bool = Field(alias="statusMismatch")
    data_problem: bool = Field(alias="dataProblem")
    nts_checked_at: datetime | None = Field(default=None, alias="ntsCheckedAt")


T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    """백엔드 `PageResponse`. `hasNext`는 서버가 내려준다 — 직접 계산하지 않는다."""

    model_config = ConfigDict(extra="ignore")

    content: list[T]
    page: int
    limit: int
    total_elements: int = Field(alias="totalElements")
    total_pages: int = Field(alias="totalPages")
    has_next: bool = Field(alias="hasNext")
