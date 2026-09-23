"""AI 서비스 HTTP 표면 (FastAPI).

**왜 서버가 필요한가.** 지금 당장은 아니다. 지금 필요한 건 백엔드를 부르는 쪽뿐이고
그건 `src/backend_client/`가 한다. 다만 조사를 실제로 돌릴 때가 되면 백엔드가 AI를
불러야 하므로, 그 자리를 미리 만들어 둔다. 엔드포인트는 하나씩 붙인다.

`GET /investigation-targets`는 **백엔드를 대신 불러 주는 얇은 층**이다. 값을 가공하지
않는다 — 지금 이 단계에서 확인할 것은 "AI 프로세스가 백엔드에 닿고 응답을 우리 모델로
파싱할 수 있는가"이기 때문이다. 조사 로직(`src/biz_number/`)을 붙이는 건 다음 작업이다.

실행:
    uv run uvicorn src.server.main:app --reload --port 8000
"""

from __future__ import annotations

from functools import lru_cache

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Body, Depends, FastAPI, HTTPException, Query, Response, status
from pydantic import ValidationError

from src.backend_client import (
    MAX_LIMIT,
    BackendClient,
    BackendError,
    NtsCheckFilter,
    Page,
    StoreCheck,
)
from src.investigation import (
    InvestigationResponse,
    InvestigationTarget,
    Investigator,
    InvestigatorUnavailable,
    StoreFinding,
    UnavailableInvestigator,
)

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


def get_investigator() -> Investigator:
    """조사 구현이 꽂히는 자리.

에이전트 1차 조사 구현이 생기면 여기 한 줄만 바꾸면 된다.
    그전까지는 `UnavailableInvestigator` 가 503 을 만든다.
    """
    return UnavailableInvestigator()


@app.get("/health")
def health() -> dict[str, str]:
    """이 프로세스가 살아 있는가. 백엔드 상태는 보지 않는다 — 둘을 섞으면
    백엔드가 죽었을 때 AI까지 죽은 것으로 보여 원인 파악이 늦어진다."""
    return {"status": "ok"}


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


@app.get("/investigation-targets", response_model=Page[StoreCheck])
def investigation_targets(
    page: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=MAX_LIMIT),
    client: BackendClient = Depends(get_client),
) -> Page[StoreCheck]:
    """AI가 조사할 가게 목록.

    `DATA_PROBLEM`(사업자번호가 없거나 틀린 건)은 사람이 고칠 몫이라 여기서 제외한다.
    """
    try:
        return client.get_nts_checks(NtsCheckFilter.STATUS_MISMATCH, page=page, limit=limit)
    except ValidationError as e:
        # 백엔드가 200 으로 답했는데 모양이 우리 모델과 다른 경우. 이 모듈이 막으려던 바로
        # 그 드리프트인데, 안 잡으면 FastAPI 기본 500 으로 빠져 로그조차 남지 않는다.
        logger.warning("백엔드 응답이 모델과 맞지 않습니다: %s", e)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"백엔드 응답을 해석하지 못했습니다 (계약 불일치): {e}",
        ) from e
    except BackendError as e:
        # 백엔드 쪽 문제를 우리 500으로 감추지 않는다. 502로 올려 원인을 드러낸다.
        # 배치로 돌릴 때는 응답을 아무도 안 보므로 로그에도 남긴다.
        logger.warning("조사 대상 조회 실패 (page=%s, limit=%s): %s", page, limit, e)
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(e)) from e


@app.post("/investigations", response_model=InvestigationResponse)
def investigate(
    targets: list[InvestigationTarget] = Body(..., min_length=1, max_length=MAX_TARGETS),
    investigator: Investigator = Depends(get_investigator),
) -> InvestigationResponse:
    """가게 목록을 받아 조사한다 — 백엔드가 AI를 부르는 자리.

    `/investigation-targets`(우리가 백엔드에서 당겨오는 것)와 방향이 반대다.
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
            results.extend(
                StoreFinding(storeId=t.store_id, failure=str(e)) for t in targets[len(results):]
            )
            break
        except Exception as e:  # noqa: BLE001 - 한 건의 예외로 배치 전체를 죽이지 않는다
            logger.warning("조사 실패 (storeId=%s): %s", target.store_id, e)
            results.append(StoreFinding(storeId=target.store_id, failure=str(e)))

    return InvestigationResponse(
        results=results,
        requested=len(targets),
        succeeded=sum(1 for r in results if r.failure is None),
    )
