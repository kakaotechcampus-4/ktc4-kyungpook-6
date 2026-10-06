"""AI 서비스 HTTP 표면 (FastAPI).

**백엔드가 AI 를 부르는 자리다.** 백엔드가 담당자가 고른 가게를 1차 결과로 나눠 한 곳씩
`POST /investigations` 로 맡기고, AI 는 조사해 돌려준다(`docs/백엔드_연동.md`). AI 가 조사 대상을
백엔드에서 가져가지는 않는다 — 예전에 있던 `GET /investigation-targets`(국세청 불일치 목록을 대신
불러 주던 것)는 그 방향이라 뺐다. 백엔드에 닿는지는 `GET /backend-health` 로 본다.

실행:
    uv run uvicorn src.server.main:app --reload --port 8000
"""

from __future__ import annotations

import os
from functools import lru_cache

import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Body, Depends, FastAPI, HTTPException, Response, status

from src.backend_client import (
    MAX_LIMIT,
    BackendClient,
    BackendError,
)
from src.investigation import (
    InvestigationResponse,
    InvestigationTarget,
    Investigator,
    InvestigatorUnavailable,
    StoreFinding,
    UnavailableInvestigator,
)
from src.investigation.failure import failure_of
from src.investigation.kakao_map import KakaoPlaceChecker
from src.investigation.web import WebInvestigator
from src.investigation.web_research import VertexResearchProvider

#: 한 번에 받을 조사 대상 수. 백엔드가 한 페이지로 가져가는 양(100)과 맞춘다.
MAX_TARGETS = MAX_LIMIT

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """종료할 때 백엔드 연결을 닫는다. 안 닫으면 reload 때마다 소켓이 남는다."""
    yield
    _close_client()
    _client.cache_clear()


app = FastAPI(
    lifespan=lifespan,
    title="store-info-agent",
    description="참여가게 정보를 공개정보와 대조해 담당자 확인 대상을 선별하는 에이전트",
    version="0.1.0",
)


@lru_cache(maxsize=1)
def _client() -> BackendClient:
    """프로세스당 하나만 만든다 — 요청마다 만들면 연결을 매번 새로 연다."""
    return BackendClient()


def _close_client() -> None:
    """lifespan 종료 시 호출. 캐시를 비우기 전에 연결을 먼저 닫는다."""
    if _client.cache_info().currsize:
        _client().close()


def get_client() -> BackendClient:
    """테스트에서 `app.dependency_overrides`로 갈아 끼우는 지점."""
    return _client()


#: 조사 구현을 고르는 환경변수.
#:   web   — 실제 웹검색 조사 (`WebInvestigator`). GOOGLE_CLOUD_PROJECT 가 있어야 한다
#:   mock  — 알려진 사례만 답하는 가짜. **연동 흐름만** 확인할 때 (외부 호출 없음)
#:   off   — 항상 503. 붙이기 전 상태를 일부러 유지할 때
#: 비워 두면 GOOGLE_CLOUD_PROJECT 유무로 정한다 — 지금까지의 동작 그대로다.
INVESTIGATOR_MODE = "INVESTIGATOR"


@lru_cache(maxsize=1)
def _web_investigator() -> Investigator:
    """Vertex 클라이언트를 프로세스당 하나만 만든다. 카카오 키가 있으면 지도 확인도 붙인다."""
    checker = KakaoPlaceChecker() if os.environ.get("KAKAO_REST_API_KEY") else None
    return WebInvestigator(VertexResearchProvider(), place_checker=checker)


def get_investigator() -> Investigator:
    """조사 구현이 꽂히는 자리 — 웹검색 2차 조사.

    Vertex 를 쓸 GCP 프로젝트(`GOOGLE_CLOUD_PROJECT`)가 없으면 `UnavailableInvestigator` 가
    503 을 만든다. 자격증명이 없다는 사실을 건별 실패 100개로 흩뿌리지 않는다.

    PR #57 머지(08c5244)에서 이 분기가 빠져 GCP 설정이 있어도 항상 503 이었다 — 테스트가
    "없으면 503" 만 보고 "있으면 웹검색 조사" 는 보지 않아 못 잡았다.

    `INVESTIGATOR` 로 덮어쓸 수 있다. 자격증명 없이 **연동 흐름만** 확인해야 할 때
    (`mock`)와, 붙이기 전 상태를 일부러 유지해야 할 때(`off`) 쓴다. 지금 무엇이 꽂혀
    있는지는 `GET /health` 가 알려 준다.
    """
    mode = os.environ.get(INVESTIGATOR_MODE, "").strip().lower()
    if mode == "off":
        return UnavailableInvestigator()
    if mode == "mock":
        from src.investigation.mock import MockInvestigator

        return MockInvestigator()
    if not mode and not os.environ.get("GOOGLE_CLOUD_PROJECT"):
        return UnavailableInvestigator()
    try:
        return _web_investigator()
    except Exception as e:  # noqa: BLE001 - 자격증명·의존성 어느 쪽이 빠져도 503 이어야 한다
        logger.warning("조사기를 만들지 못했습니다: %s", e)
        return UnavailableInvestigator()


def investigator_mode() -> str:
    """지금 어떤 조사기가 꽂혀 있는가. `/health` 가 쓴다."""
    return type(get_investigator()).__name__


def investigator_mode() -> str:
    """지금 어떤 조사기가 꽂혀 있는가. `/health` 가 쓴다."""
    impl = get_investigator()
    return type(impl).__name__


@app.get("/health")
def health() -> dict[str, str]:
    """이 프로세스가 살아 있는가. 백엔드 상태는 보지 않는다 — 둘을 섞으면
    백엔드가 죽었을 때 AI까지 죽은 것으로 보여 원인 파악이 늦어진다.

    **어떤 조사기가 꽂혀 있는지 같이 알려 준다.** 503 이 날 때 "자격증명이 없어서"인지
    "코드가 안 꽂혀서"인지를 배포 환경에서 확인할 길이 이것뿐이다.
    """
    return {"status": "ok", "investigator": investigator_mode()}


@app.get("/backend-health")
def backend_health(
    response: Response, client: BackendClient = Depends(get_client)
) -> dict[str, str]:
    """백엔드에 닿는가. 가장 가벼운 조회 하나로 확인한다.

    **닿지 않으면 503으로 답한다.** 본문에만 `unreachable`을 적고 200을 주면
    모니터링·컨테이너 프로브는 본문을 읽지 않으므로 "정상"으로 집계된다.
    """
    try:
        client.get_stores(page=0, limit=1)
    except BackendError as e:
        logger.warning("백엔드 헬스체크 실패: %s", e)
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {"status": "unreachable", "detail": str(e)}
    return {"status": "ok"}


@app.post("/investigations", response_model=InvestigationResponse)
def investigate(
    targets: list[InvestigationTarget] = Body(..., min_length=1, max_length=MAX_TARGETS),
    investigator: Investigator = Depends(get_investigator),
) -> InvestigationResponse:
    """가게 목록을 받아 조사한다 — 백엔드가 AI를 부르는 자리.

    요청은 백엔드 `GET /api/stores/nts-checks` 응답 행을 그대로 담으면 된다.
    담당자가 가게를 골라 조사를 시작하는 흐름은 이쪽을 쓴다.

    **한 건이 실패해도 나머지는 계속 처리하고, 실패한 건도 결과에 남긴다.**
    요청 수와 응답 수가 달라지면 부르는 쪽이 무엇이 빠졌는지 알 수 없다.
    조사 구현 자체가 없으면 그건 건별 실패가 아니라 요청 전체의 실패라 503 으로 답한다.
    """
    results: list[StoreFinding] = []
    for target in targets:
        try:
            results.append(investigator.investigate(target))
        except InvestigatorUnavailable as e:
            # 첫 건에서 났으면 요청 전체가 못 도는 것이라 503 이 맞다. 그런데 100건 중
            # 99건을 처리한 뒤 자격증명이 만료돼 났다면, 503 을 던지는 순간 이미 끝낸
            # 99건이 통째로 버려진다 — "한 건이 실패해도 빼지 않는다"는 이 API 의 약속과
            # 어긋난다. 그래서 이미 쌓인 결과가 있으면 남은 건만 실패로 적고 돌려준다.
            logger.warning("조사 구현을 쓸 수 없습니다 (처리 완료 %s건): %s", len(results), e)
            if not results:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e)
                ) from e
            failure = failure_of(e)
            results.extend(StoreFinding(storeId=t.store_id, failure=failure) for t in targets[len(results):])
            break
        except Exception as e:  # noqa: BLE001 - 한 건의 예외로 배치 전체를 죽이지 않는다
            # 응답에는 종류(code)와 고정 문장만 나간다 — 원래 예외 문장은 여기 로그로만 남긴다.
            logger.warning("조사 실패 (storeId=%s): %s", target.store_id, e)
            results.append(StoreFinding(storeId=target.store_id, failure=failure_of(e)))

    return InvestigationResponse(
        results=results,
        requested=len(targets),
        succeeded=sum(1 for r in results if r.failure is None),
    )
