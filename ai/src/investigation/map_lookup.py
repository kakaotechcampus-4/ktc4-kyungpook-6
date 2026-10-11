"""지도 대조 — 지도 서비스에 지금 등록된 상호·주소·전화를 관측으로 가져온다. LLM 을 쓰지 않는다.

    네이버 지역 검색 API (NAVER API HUB)  상호·도로명 주소. 전화는 빈 값으로 온다
    카카오 로컬 키워드 검색               상호·도로명 주소·전화. DB 좌표 반경 150m

담당자가 원래 지도에서 가게를 찾아 대조하던 일을 대신한다. 불일치 벤치마크(일부러 틀리게 넣은 34곳)에서
웹검색만으로는 10곳, 지도를 먼저 보고 안 되면 웹을 쓰면 22곳을 잡았다(`eval/mismatch_bench.py`, 2026-10-06).

**같은 가게 고르기** — 상호가 비슷한 후보만(한쪽이 다른 쪽을 품거나 글자 유사도 0.6 이상, DB 상호의 오타를 감안),
두 글자 이하 상호는 정확히 같아야 하고, 시·도와 구·군이 DB 와 같아야 한다. 주소만 같은 후보는 고르지 않는다 —
같은 건물의 다른 업체(어린이집·교회·경비실)를 잡았다.

**약관** — 카카오 값은 저장할 수 없다(실시간 비교 후 폐기, 장소 링크만 저장 허용). 그래서 카카오 관측은
`storable=False` 로 내보내 판정에만 쓰고, 수정안에는 값 대신 "지도 등록 정보와 다름 + 링크"만 남는다
(`classify.py`). 카카오 값은 LLM 에도 넘기지 않는다. 네이버 검색 API 약관(목적 제한·저장 금지, 기사 기준)은
원문 확인 전이라 스위치로 끄고 켠다.
"""

from __future__ import annotations

import logging
import os
import re
from datetime import date
from difflib import SequenceMatcher
from urllib.parse import quote

import httpx

from src.investigation.classify import comparison_key
from src.investigation.kakao_map import KakaoPlaceChecker, search_name
from src.investigation.models import ChangeField, InvestigationTarget
from src.investigation.web_research import Observation, Source

NAVER_LOCAL_URL = "https://naverapihub.apigw.ntruss.com/search/v1/local"
TIMEOUT_SECONDS = 5.0
_TAG = re.compile(r"<[^>]+>")

logger = logging.getLogger(__name__)


def _region(address: str) -> tuple[str, str]:
    """("대구", "남구"). 시·도는 앞 두 글자로 맞춘다(대구광역시 = 대구)."""
    parts = address.split()
    return (parts[0][:2] if parts else "", parts[1] if len(parts) > 1 else "")


def same_store(target: InvestigationTarget, name: str, road_address: str) -> bool:
    """지도 후보가 이 가게인가."""
    if road_address and target.address and _region(road_address) != _region(target.address):
        return False
    a = comparison_key(ChangeField.NAME, search_name(target.name))
    b = comparison_key(ChangeField.NAME, name)
    if not a or not b:
        return False
    if min(len(a), len(b)) <= 2:
        return a == b
    return a in b or b in a or SequenceMatcher(None, a, b).ratio() >= 0.6


def _observe(field: ChangeField, value: str, domain: str, url: str, *, storable: bool, label: str) -> Observation:
    # 지도 정보는 지금 등록된 값이라 오늘 날짜로 본다 — 날짜 규칙에서 오래된 글보다 앞선다.
    evidence = f"{label}에 등록된 값 {value}" if storable else f"{label}에 등록된 값"
    return Observation(field=field, value=value, evidence=evidence, observed_at=date.today().isoformat(),
                       sources=(Source(domain=domain, url=url),), storable=storable)


class NaverLocalLookup:
    def __init__(self, *, http: httpx.Client | None = None) -> None:
        self._http = http if http is not None else httpx.Client(timeout=TIMEOUT_SECONDS, headers={
            "X-NCP-APIGW-API-KEY-ID": os.environ.get("NAVER_CLIENT_ID", ""),
            "X-NCP-APIGW-API-KEY": os.environ.get("NAVER_CLIENT_SECRET", ""),
        })

    def observe(self, target: InvestigationTarget) -> list[Observation]:
        name = search_name(target.name)
        if not name:
            return []
        parts = (target.address or "").split()
        query = f"{name} {parts[1]}" if len(parts) > 1 else name
        response = self._http.get(NAVER_LOCAL_URL, params={"query": query, "display": 5})
        response.raise_for_status()
        for item in response.json().get("items", []):
            title = _TAG.sub("", item.get("title", ""))
            road = item.get("roadAddress", "")
            if not same_store(target, title, road):
                continue
            # 네이버 검색 API 는 장소 링크를 주지 않는다 — 담당자가 누를 지도 검색 링크를 만든다.
            url = "https://map.naver.com/p/search/" + quote(f"{title} {road}")
            out = [_observe(ChangeField.NAME, title, "map.naver.com", url, storable=True, label="네이버 지도")]
            if road:
                out.append(_observe(ChangeField.ADDRESS, road, "map.naver.com", url, storable=True, label="네이버 지도"))
            return out
        return []


class KakaoLocalLookup(KakaoPlaceChecker):
    def observe(self, target: InvestigationTarget) -> list[Observation]:
        if not self._api_key or not search_name(target.name):
            return []
        coordinates = self._coordinates(target)
        if coordinates is None:
            return []
        # 상호로 찾고, 상호가 틀렸을 수 있으니 못 찾으면 반경 안 음식점을 거리순으로 본다(상호 유사도로 거름).
        for query in (search_name(target.name), "음식점"):
            places = self._get("keyword.json", {"query": query, "x": coordinates[0], "y": coordinates[1],
                                                "radius": 150, "sort": "distance"})
            for place in places:
                if not same_store(target, place["place_name"], place.get("road_address_name", "")):
                    continue
                url = place.get("place_url") or "https://map.kakao.com"
                out = [_observe(ChangeField.NAME, place["place_name"], "map.kakao.com", url, storable=False, label="카카오맵")]
                if place.get("road_address_name"):
                    out.append(_observe(ChangeField.ADDRESS, place["road_address_name"], "map.kakao.com", url,
                                        storable=False, label="카카오맵"))
                if place.get("phone"):
                    out.append(_observe(ChangeField.PHONE, place["phone"], "map.kakao.com", url,
                                        storable=False, label="카카오맵"))
                return out
        return []


class MapLookup:
    """켜진 지도 출처를 모두 부른다. 한 출처가 실패해도 나머지는 쓴다 — 지도 대조는 조사를 막지 않는다."""

    def __init__(self, sources: list) -> None:
        self._sources = sources

    @property
    def uses_kakao(self) -> bool:
        """카카오 값 대조가 켜져 있는가 — 켜져 있으면 예전 "근처에 있다/없다" 확인(`KakaoPlaceChecker`)은 겹친다."""
        return any(isinstance(s, KakaoLocalLookup) for s in self._sources)

    @classmethod
    def from_env(cls) -> MapLookup | None:
        """`NAVER_LOCAL_ENABLED`·`KAKAO_COMPARE_ENABLED` 가 "true" 인 출처만. 둘 다 꺼져 있으면 None."""
        sources = []
        if os.environ.get("NAVER_LOCAL_ENABLED", "").lower() == "true" and os.environ.get("NAVER_CLIENT_ID"):
            sources.append(NaverLocalLookup())
        if os.environ.get("KAKAO_COMPARE_ENABLED", "").lower() == "true" and os.environ.get("KAKAO_REST_API_KEY"):
            sources.append(KakaoLocalLookup())
        return cls(sources) if sources else None

    def observe(self, target: InvestigationTarget) -> list[Observation]:
        observations = []
        for source in self._sources:
            try:
                observations += source.observe(target)
            except (httpx.HTTPError, ValueError, KeyError) as e:
                logger.info("지도 대조 실패 %s (storeId=%s): %s", type(source).__name__, target.store_id, e)
        return observations
