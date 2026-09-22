"""백엔드 호출 클라이언트.

AI 쪽에서 백엔드를 **부르는** 방향만 담당한다. 반대 방향(백엔드가 AI를 부르는 것)은
`src/server/`가 맡는다. 둘을 한 파일에 섞지 않는다 — 부르는 쪽과 불리는 쪽은
장애 지점도, 테스트 방법도 다르다.

주소는 `BACKEND_BASE_URL`로 받는다. 기본값은 로컬 백엔드(`http://localhost:8080`)다.
docker compose로 띄우면 서비스명(`http://backend:8080`)이 되므로 반드시 환경변수로 준다.
"""

from __future__ import annotations

import os

import httpx

from src.backend_client.models import NtsCheckFilter, Page, Store, StoreCheck

DEFAULT_BASE_URL = "http://localhost:8080"

# 외부 API가 아니라 우리 백엔드라 빨라야 정상이다. 오래 매달리면 배치가 통째로 밀린다.
DEFAULT_TIMEOUT_SECONDS = 10.0

# 백엔드 `StoreController.MAX_LIMIT` 와 같은 값이어야 한다. 넘겨 보내면 백엔드가 400을 낸다.
# 두 곳에 같은 숫자가 있으므로 `tests/backend_client/test_contract.py` 가 Java 원본과 대조한다.
MAX_LIMIT = 100


class BackendError(RuntimeError):
    """백엔드가 2xx로 답하지 않았거나 아예 닿지 않았을 때.

    호출한 쪽이 "백엔드 문제"와 "우리 파싱 문제"를 구분할 수 있게 따로 둔다.
    """


class BackendClient:
    """백엔드 조회 API 클라이언트.

    `httpx.Client`를 주입받을 수 있게 열어 둔 건 테스트 때문이다 —
    `httpx.MockTransport`를 끼우면 네트워크 없이 계약을 검증할 수 있다.
    """

    def __init__(
        self,
        base_url: str | None = None,
        *,
        client: httpx.Client | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> None:
        self._base_url = (base_url or os.environ.get("BACKEND_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
        self._client = client or httpx.Client(base_url=self._base_url, timeout=timeout)
        self._owns_client = client is None

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> BackendClient:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    def _get(self, path: str, params: dict[str, object]) -> dict:
        # None인 파라미터는 아예 보내지 않는다. 빈 값으로 보내면 백엔드가 enum 파싱에서 400을 낸다.
        query = {k: v for k, v in params.items() if v is not None}
        try:
            response = self._client.get(path, params=query)
        except httpx.HTTPError as e:
            raise BackendError(f"백엔드에 닿지 못했습니다: {self._base_url}{path} ({e})") from e

        if response.status_code >= 400:
            raise BackendError(
                f"백엔드가 {response.status_code}로 응답했습니다: "
                f"{self._base_url}{path} — {response.text[:200]}"
            )
        return response.json()

    def get_stores(self, page: int = 0, limit: int = 20) -> Page[Store]:
        """가게 목록. 화면이 쓰는 것과 같은 API다."""
        return Page[Store].model_validate(self._get("/api/stores", {"page": page, "limit": limit}))

    def get_nts_checks(
        self,
        nts_filter: NtsCheckFilter | None = None,
        page: int = 0,
        limit: int = 20,
    ) -> Page[StoreCheck]:
        """국세청 대조 결과가 붙은 가게 목록 — AI 조사에 넘길 자료.

        이 API는 국세청을 직접 부르지 않고 새벽 배치가 저장해 둔 기록을 읽는다.
        그래서 외부 API 장애와 무관하게 응답한다.

        `filter=STATUS_MISMATCH`만 조사 대상이다. `DATA_PROBLEM`은 사업자번호 자체가
        없거나 틀린 건이라 **사람이 고칠 몫이고, 조사에 넘기면 엉뚱한 가게를 보게 된다.**
        """
        return Page[StoreCheck].model_validate(
            self._get(
                "/api/stores/nts-checks",
                {"filter": nts_filter.value if nts_filter else None, "page": page, "limit": limit},
            )
        )
