"""조사 요청·결과의 모양.

**이 파일이 정하는 건 "무엇을 주고받는가"뿐이다.** 조사 로직은 `Investigator` 구현이
맡는다(`web.py` — 웹검색 2차 조사).

흐름: 담당자가 가게 선택 → 백엔드 1차 조사(국세청 대조) → **국세청으로 변화가 감지되지
않은 가게**(대조 결과 일치, 또는 조회 실패)만 백엔드가 골라 보내고, AI 는 받은 가게를 전부 웹검색으로
2차 조사한다. 대상을 거르는 건 백엔드 몫이다 — AI 는 다시 거르지 않는다.

근거를 결과에 함께 담는 이유 — 담당자가 전화로 확인하기 전에 "왜 이렇게 판단했는지"를
읽어야 하기 때문이다. 값만 돌려주면 사람이 검증할 수 없다. 2차 조사는 근거를 `Signal`(잡힌 변화 하나 +
대표 근거)에 담는다.

**응답은 백엔드 스키마 그대로 나간다** — `classification`·`proposedChanges` 는 `Task` 칸, `signals[]` 는
`Signal` 칸(`signalType`·`confidence`·`evidenceText`·`evidenceUrl`)과 추가를 요청한 `field` 만 담아 백엔드가 바꾸지
않고 저장할 수 있게 한다. 백엔드에 칸이 없는 값(`observed`·`sourceCount`·`mapCheck`)은 규칙·평가에만 쓰고
응답에서는 뺀다(`exclude=True`). 담당자가 봐야 하는 것(출처 수, 지도와 어긋남)은 `evidenceText` 문구에 넣는다.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field

from src.backend_client.models import StoreStatus


class TaskClassification(str, Enum):
    """백엔드 `TaskClassification` 을 옮긴 것. 화면 라벨은 괄호 안."""

    #: 우선확인 — 변화가 잡혔다(1차: 국세청, 2차: 웹검색). 출처 수와 상관없이 담당자가 수정안을 보고 승인한다.
    PRIORITY_CHECK = "PRIORITY_CHECK"
    #: 추가확인 — 변화없는 매장 중 직접 확인한 지 90일 이상 지난 매장. 백엔드가 정하고 AI 는 돌려주지 않는다.
    ADDITIONAL_CHECK = "ADDITIONAL_CHECK"
    NO_CHANGE = "NO_CHANGE"  # 변화없음


class SignalType(str, Enum):
    """백엔드 `SignalType` 을 옮긴 것. 화면 라벨은 괄호 안.

    2차 조사는 등급을 나누지 않는다 — 변화 Signal 은 전부 `SIGNAL_HIGH` 이고 `SIGNAL_LOW`·`SIGNAL_NONE` 은
    쓰지 않는다(`classify.py`). 백엔드와 값을 맞추려고 남겨 둔다.
    """

    SIGNAL_HIGH = "SIGNAL_HIGH"  # 우선검토필요
    SIGNAL_LOW = "SIGNAL_LOW"  # 추가검토권장
    SIGNAL_NONE = "SIGNAL_NONE"  # 변화근거부족


class ChangeField(str, Enum):
    """2차 조사가 보는 항목. 값은 `Task.proposedChanges` 의 키로 쓰는 가게 필드명이다."""

    STATUS = "status"
    PHONE = "phone"
    ADDRESS = "addressRoad"
    NAME = "name"


class InvestigationTarget(BaseModel):
    """조사할 가게 한 건. 백엔드가 가진 값을 그대로 넘겨받는다.

    `storeId`만 받고 우리가 백엔드에서 다시 긁어오지 않는 이유 — 부르는 쪽이 이미
    들고 있는 값이고, 왕복을 한 번 더 하면 그 사이에 값이 바뀔 수 있다.
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    store_id: int = Field(alias="storeId")
    name: str
    #: 백엔드 `nts-checks` 응답의 `addressRoad` 를 그대로 받는다.
    #: 이름을 `address` 로만 두면 백엔드가 그 응답을 되돌려줄 때 `extra="ignore"` 에
    #: 먹혀 **조용히 None 이 된다** — 주소 없이 상호명만으로 검색하면 동명의 엉뚱한
    #: 가게를 찾는다. 422 도 안 나서 알아채기 어렵다.
    address: str | None = Field(default=None, alias="addressRoad")
    biz_no: str | None = Field(default=None, alias="bizNo")
    #: 웹에서 찾은 값과 비교할 우리 DB 값. 없으면 그 항목은 "새로 찾은 값"이 된다.
    phone: str | None = None
    internal_status: StoreStatus | None = Field(default=None, alias="internalStatus")
    #: 가게 좌표(WGS84). 있으면 카카오맵 확인이 주소를 좌표로 바꾸지 않고 바로 쓴다.
    lat: float | None = None
    lng: float | None = None


class PlaceStatus(str, Enum):
    """카카오맵 확인 결과(`kakao_map.py`)."""

    FOUND = "FOUND"  # DB 주소 150m 안에 같은 상호가 있다
    NOT_FOUND = "NOT_FOUND"  # 좌표는 얻었는데 같은 상호가 없다 — 위치 미확인
    NO_COORDINATES = "NO_COORDINATES"  # DB 주소를 좌표로 못 바꿨다
    UNAVAILABLE = "UNAVAILABLE"  # 키가 없거나 호출이 실패했다


class PlaceCheck(BaseModel):
    """카카오맵 확인 한 건. 카카오 응답에서 남기는 건 이것뿐이다 — 장소명·전화·좌표는 버린다."""

    model_config = ConfigDict(populate_by_name=True)

    status: PlaceStatus
    #: 찾았을 때 카카오맵 장소 페이지. 담당자가 눌러 직접 확인한다(저장 허용 항목).
    place_url: str | None = Field(default=None, alias="placeUrl")


class Signal(BaseModel):
    """잡힌 변화 하나와 그 대표 근거. 백엔드 `Signal` 한 행("이상 징후 하나")에 대응한다.

    DB 와 다른 값이 잡힌 항목마다 한 행이다(`SIGNAL_HIGH`). 변화가 없으면 Signal 은 없다.
    응답에는 백엔드 `Signal` 칸과 `field` 만 나간다. `observed`·`sourceCount` 는 규칙·평가용이다.
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    signal_type: SignalType = Field(alias="signalType")
    #: **항상 null 이다.** 모델이 매긴 확신도를 판단 근거로 쓰지 않기로 했다(멘토 결정). 백엔드
    #: `Signal.confidence` 는 지금 NOT NULL 이라 nullable 로 바꿔 달라고 요청한다(`docs/백엔드_연동.md`).
    confidence: float | None = None
    evidence_text: str = Field(alias="evidenceText")
    evidence_url: str | None = Field(default=None, alias="evidenceUrl")
    #: 어느 항목의 변화인가. 값은 `Task.proposedChanges` 의 키와 같다 — 수정안이 여럿일 때 근거와 짝짓는다.
    #: 백엔드 `signal.field` 컬럼은 추가를 요청했다(`docs/백엔드_연동.md`). 컬럼이 생기기 전에는 백엔드가 무시한다.
    field: ChangeField | None = None
    #: 웹에서 찾은 새 값 — 수정안에 오르는 값(`proposedChanges` 의 값과 같다). 응답에서는 뺀다.
    observed: str | None = Field(default=None, exclude=True)
    #: 이 값을 가리킨 서로 다른 출처(도메인) 수. 응답에서는 빼고 `evidenceText` 문구에 적는다.
    source_count: int = Field(default=0, alias="sourceCount", exclude=True)


class StoreFinding(BaseModel):
    """가게 한 건의 조사 결과.

    조사가 실패해도 **결과를 빼지 않고 `failure`를 채워 돌려준다.** 요청한 건수와
    받은 건수가 달라지면 부르는 쪽이 무엇이 빠졌는지 알 수 없다 —
    국세청 배치에서 같은 문제로 멘토 지적을 받았다.

    판정 필드는 백엔드가 저장하는 모양을 따른다 — `classification`·`proposedChanges` 는
    `Task`, `signals` 는 `Signal` 행(잡힌 변화 하나씩)이다. 실패한 가게(`failure`)는 판정이 없으므로
    백엔드는 Task 를 만들지 않고 `Job.errorMessage` 에 적는다(`Task.classification` 이 NOT NULL).

    **`proposedChanges` 는 제안이다.** 우선확인이어도 담당자가 승인해야 가게 정보가 바뀐다.
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    store_id: int = Field(alias="storeId")
    #: 실패한 건은 판정이 없으므로 None.
    classification: TaskClassification | None = None
    #: 잡힌 변화마다 새 값 하나. 키는 `ChangeField` 값(가게 필드명)이고 `signals` 의 `field` 와 짝이다.
    #: 예: {"status": "CLOSED", "phone": "053-000-0000"}
    proposed_changes: dict[str, str] = Field(default_factory=dict, alias="proposedChanges")
    signals: list[Signal] = Field(default_factory=list)
    #: 카카오맵 확인 결과. 확인하지 않았으면 None. 분류는 바꾸지 않는다(`classify.py`).
    #: 응답에서는 뺀다 — 백엔드에 칸이 없다. 지도와 어긋나면 `evidenceText` 에 적힌다.
    map_check: PlaceCheck | None = Field(default=None, alias="mapCheck", exclude=True)
    #: 실패 사유. 성공이면 None.
    failure: str | None = None


class InvestigationResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    results: list[StoreFinding]
    requested: int
    succeeded: int
