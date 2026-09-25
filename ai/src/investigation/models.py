"""조사 요청·결과의 모양.

**이 파일이 정하는 건 "무엇을 주고받는가"뿐이다.** 에이전트 1차 조사 로직은
아직 아무도 시작하지 않았다. 구현이 생기면 `Investigator`를 구현해 끼우기만 하면
되도록, 경계를 먼저 고정해 둔다.

근거(`Evidence`)를 결과에 함께 담는 이유 — 담당자가 전화로 확인하기 전에
"왜 이렇게 판단했는지"를 읽어야 하기 때문이다. 값만 돌려주면 사람이 검증할 수 없다.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class InvestigationTarget(BaseModel):
    """조사할 가게 한 건. 백엔드가 가진 값을 그대로 넘겨받는다.

    `storeId`만 받고 우리가 백엔드에서 다시 긁어오지 않는 이유 — 부르는 쪽이 이미
    들고 있는 값이고, 왕복을 한 번 더 하면 그 사이에 값이 바뀔 수 있다.
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    store_id: int = Field(alias="storeId")
    name: str
    #: 백엔드가 `/investigation-targets` 로 돌려준 `addressRoad` 를 그대로 받는다.
    #: 이름을 `address` 로만 두면 백엔드가 그 응답을 되돌려줄 때 `extra="ignore"` 에
    #: 먹혀 **조용히 None 이 된다** — 주소 없이 상호명만으로 검색하면 동명의 엉뚱한
    #: 가게를 찾는다. 422 도 안 나서 알아채기 어렵다.
    address: str | None = Field(default=None, alias="addressRoad")
    biz_no: str | None = Field(default=None, alias="bizNo")


class Evidence(BaseModel):
    """판단 근거 한 줄. 출처가 없으면 사람이 검증할 수 없으므로 `source`는 필수다."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    source: str  # 예: "비즈노 상호명 검색", "웹검색"
    detail: str
    url: str | None = None


class StoreFinding(BaseModel):
    """가게 한 건의 조사 결과.

    조사가 실패해도 **결과를 빼지 않고 `failure`를 채워 돌려준다.** 요청한 건수와
    받은 건수가 달라지면 부르는 쪽이 무엇이 빠졌는지 알 수 없다 —
    국세청 배치에서 같은 문제로 멘토 지적을 받았다.

    **판정 결과를 담을 필드는 아직 없다.** 회의에서 조사 흐름이 정리되는 중이라
    (1차 조사는 백엔드 배치, 2차 조사는 웹서치, 사업자번호 채우기는 별도 흐름),
    무엇을 돌려줄지 확정되면 그때 더한다. 지금 추측해서 넣으면 백엔드가 저장하는
    `Task`(분류·수정안)·`Signal`(근거·신뢰도)과 어긋난 채로 굳는다.

    빼는 것보다 더하는 것이 안전해서, 어떤 조사든 공통인 것만 남겼다.
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    store_id: int = Field(alias="storeId")
    evidences: list[Evidence] = Field(default_factory=list)
    #: 실패 사유. 성공이면 None.
    failure: str | None = None


class InvestigationResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    results: list[StoreFinding]
    requested: int
    succeeded: int
