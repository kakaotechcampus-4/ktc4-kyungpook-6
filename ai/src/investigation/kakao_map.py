"""카카오맵에 우리 가게가 지금도 등록되어 있는가 — 2차 조사의 지도 확인 단계.

DB 좌표(없으면 DB 주소를 주소 검색으로 바꾼 좌표) 반경 150m 안에서 상호로 찾는다(키워드 검색).
간이 DB 60곳 실측: 인허가 폐업 30곳은 **0곳**, 영업 30곳은 17곳이 찾아졌다 — 찾아지면 영업 중이라는
근거로 17/17 이 맞았다. 반대로 **못 찾았다고 폐업은 아니다**(영업 가게 43% 도 못 찾음). 그래서 찾았을
때만 근거로 쓰고, 못 찾으면 "위치 미확인"일 뿐이다(멘토 설계: 카카오는 올리는 데만, 기각하지 않는다).

**응답에서 꺼내 쓰는 건 "찾았는가"와 `place_url` 뿐이다.** 장소명·전화·좌표는 비교에만 쓰고 버린다.
카카오맵 담당자 답변(데브톡, 2026-09): 실시간 비교 후 즉시 폐기는 허용, 장소 ID·URL 은 저장 허용,
장소명·가공 데이터 저장과 **외부 LLM 전송은 불허**. 그래서 이 단계는 모델(Gemini)을 거치지 않고
규칙으로만 돈다. 지금은 쓸 만한지 재는 단계다 — 정식으로 쓰기 전에 약관을 다시 확인한다.
"""

from __future__ import annotations

import logging
import os
import re
import httpx

from src.investigation.classify import comparison_key
from src.investigation.models import ChangeField, InvestigationTarget, PlaceCheck, PlaceStatus

API_BASE = "https://dapi.kakao.com/v2/local/search"
RADIUS_METERS = 150
TIMEOUT_SECONDS = 5.0

# 검색어에서 뺄 것 — 법인 표기·괄호 병기·층수. 인허가·명단 상호에 자주 붙는다
# ("롯데컬처웍스(주)…(7층)", "주식회사 대신에프앤").
_NOISE = re.compile(r"\(.*?\)|\[.*?\]|주식회사|㈜")

logger = logging.getLogger(__name__)


def search_name(name: str) -> str:
    return _NOISE.sub(" ", name).strip()


def same_place_name(ours: str, theirs: str) -> bool:
    """카카오 장소명이 우리 상호와 같은가. 한쪽이 다른 쪽을 품으면 같다("은정" ⊂ "은정식당").

    엄격하게 둔다 — 느슨하게 맞추면 150m 안의 다른 가게를 잡는다.
    """
    a = comparison_key(ChangeField.NAME, search_name(ours))
    b = comparison_key(ChangeField.NAME, theirs)
    return bool(a and b) and (a in b or b in a)


class KakaoPlaceChecker:
    def __init__(self, api_key: str | None = None, *, http: httpx.Client | None = None) -> None:
        self._api_key = api_key if api_key is not None else os.environ.get("KAKAO_REST_API_KEY")
        self._http = http if http is not None else httpx.Client(timeout=TIMEOUT_SECONDS)

    def check(self, target: InvestigationTarget) -> PlaceCheck:
        """실패해도 예외를 올리지 않는다 — 지도 확인은 보조 근거라 조사 전체를 실패시키지 않는다."""
        # 상호가 "(주)" 처럼 법인 표기뿐이면 검색어가 빈다 — 빈 검색어로 부르면 카카오가 400 을 준다.
        if not self._api_key or not search_name(target.name):
            return PlaceCheck(status=PlaceStatus.UNAVAILABLE)
        if target.lat is None or target.lng is None:
            if not target.address:
                return PlaceCheck(status=PlaceStatus.NO_COORDINATES)
        try:
            return self._check(target)
        except (httpx.HTTPError, ValueError, KeyError) as e:
            logger.info("카카오맵 확인 실패 (storeId=%s): %s", target.store_id, e)
            return PlaceCheck(status=PlaceStatus.UNAVAILABLE)

    def _get(self, path: str, params: dict[str, object]) -> list[dict]:
        response = self._http.get(
            f"{API_BASE}/{path}", params=params, headers={"Authorization": f"KakaoAK {self._api_key}"}
        )
        response.raise_for_status()
        return response.json().get("documents", [])

    def _coordinates(self, target: InvestigationTarget) -> tuple[float, float] | None:
        """(x=경도, y=위도). DB 좌표가 있으면 그대로, 없으면 DB 주소를 주소 검색으로 바꾼다."""
        if target.lat is not None and target.lng is not None:
            return target.lng, target.lat
        addresses = self._get("address.json", {"query": target.address})
        return (float(addresses[0]["x"]), float(addresses[0]["y"])) if addresses else None

    def _check(self, target: InvestigationTarget) -> PlaceCheck:
        coordinates = self._coordinates(target)
        if coordinates is None:
            return PlaceCheck(status=PlaceStatus.NO_COORDINATES)
        places = self._get(
            "keyword.json",
            {
                "query": search_name(target.name),
                "x": coordinates[0],
                "y": coordinates[1],
                "radius": RADIUS_METERS,
                "sort": "distance",
            },
        )
        for place in places:
            if same_place_name(target.name, place["place_name"]):
                return PlaceCheck(status=PlaceStatus.FOUND, place_url=place.get("place_url"))
        return PlaceCheck(status=PlaceStatus.NOT_FOUND)
