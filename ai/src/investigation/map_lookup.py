"""지도 대조 — 지도 서비스에 지금 등록된 상호·주소·전화를 관측으로 가져온다. LLM 을 쓰지 않는다.

    네이버 지역 검색 API (NAVER API HUB)  상호·도로명 주소. 전화는 빈 값으로 온다
    카카오 로컬 키워드 검색               상호·도로명 주소·전화. DB 좌표 반경 150m

담당자가 원래 지도에서 가게를 찾아 대조하던 일을 대신한다. 불일치 벤치마크(일부러 틀리게 넣은 34곳)에서
웹검색만으로는 10곳, 지도를 먼저 보고 안 되면 웹을 쓰면 22곳을 잡았다(`eval/mismatch_bench.py`, 2026-10-06).

**같은 가게 고르기** — 상호가 비슷한 후보만(한쪽이 다른 쪽을 품거나 글자 유사도 0.6 이상, DB 상호의 오타를 감안),
두 글자 이하 상호는 정확히 같아야 하고, 시·도와 구·군, 그리고 읍·면·동이나 큰길 이름이 DB 와 같아야 한다.
주소만 같은 후보는 고르지 않는다 —
같은 건물의 다른 업체(어린이집·교회·경비실)를 잡았다.

**약관** — 두 지도 모두 값을 저장할 수 없다. 카카오는 실시간 비교 후 폐기·장소 링크 저장만 허용하고, 네이버
검색 API(API HUB 약관 2026-09-20)는 저장·캐싱·AI 입력과 "지역정보를 별도 데이터베이스로 관리"하는 것을 금지한다.
그래서 지도 관측은 `storable=False` 로 내보내 판정에만 쓰고, 수정안에는 값 대신 "지도 등록 정보와 다름 + 링크"만
남는다(`classify.py`). 값은 LLM 에도 넘기지 않는다(`agent.py`). 네이버 목적 제한(검색결과 제공 목적으로만)은 남아
스위치로 끄고 켠다(`docs/2차_조사.md`).
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


#: 읍·면, 그리고 동(칠성동1가 → 칠성동). 건물 동 번호("303동")는 숫자로 시작해 걸리지 않는다.
_LOCALITY = re.compile(r"[가-힣]+(?:읍|면)(?![가-힣])|[가-힣]+동(?=\d+가|[^가-힣]|$)")
#: 도로명의 큰길 이름 — "비슬로525길" → "비슬로". 번길·길 번호는 같은 큰길에서 갈라진 골목이다.
_ROAD_NAME = re.compile(r"([가-힣A-Za-z0-9]+?(?:로|길))\s*\d")
_BRANCH_ROAD = re.compile(r"\d+번?길$")


def _localities(address: str) -> set[str]:
    """주소의 읍·면·동 이름과 큰길 이름."""
    roads = {_BRANCH_ROAD.sub("", m[1]) for m in _ROAD_NAME.finditer(address)}
    return set(_LOCALITY.findall(address)) | {r for r in roads if r}


def _same_locality(a: str, b: str) -> bool:
    """두 주소가 같은 동네인가 — 읍·면·동이나 큰길 이름 중 하나라도 같으면. 한쪽에 비교할 것이 없으면 막지 않는다.

    시·도와 구·군만 보면 군 단위가 넓어 다른 읍의 같은 이름 가게를 같은 가게로 골랐다(실측: 화원읍 상하이반점에
    가창면 상하이반점, 다사읍 시골뚝배기에 유가읍 시골뚝배기). DB 값이 맞는 60곳 중 4곳이 이렇게 "주소 다름"이 됐다.
    다른 동으로 이전한 가게는 못 찾게 되지만, 변화는 드물고 엉뚱한 주소를 올리는 것보다 낫다.
    """
    left, right = _localities(a), _localities(b)
    return not left or not right or bool(left & right)


#: 바로 옆이라고 볼 거리(m). 이 안이면 짧은 상호가 후보 이름에 들어 있기만 해도 같은 가게로 본다("은정" ↔ "은정식당").
#: 거리 없이 넓게 허용하면 다른 가게를 잡는다(E1(이원) ↔ "SK에너지 E1충전소").
NEXT_DOOR_METERS = 20
#: "네네치킨앤봉구스밥버거"처럼 두 상호를 이은 가게는 지도에 한쪽만 등록된다 — 그 한쪽과 같고 이 거리 안이면 같은 가게.
JOINED_BRAND_METERS = 30
_BRANCH = re.compile(r"[(（]([^)）]*)[)）]")


def _name_key(name: str) -> str:
    return comparison_key(ChangeField.NAME, search_name(name))  # & ↔ 앤 은 comparison_key 가 맞춘다


def _similar_name(target: InvestigationTarget, name: str, distance: float | None = None) -> bool:
    a, b = _name_key(target.name), _name_key(name)
    if not a or not b:
        return False
    if min(len(a), len(b)) <= 2:
        if a == b:
            return True
        # 짧은 상호는 괄호 속 지점명까지 후보 이름에 있어야 한다("행컵(계명대점)" ↔ "행컵 계명대점").
        branch = _BRANCH.search(target.name)
        if branch and b.startswith(a) and _name_key(branch[1]) and _name_key(branch[1]) in b:
            return True
        return distance is not None and distance <= NEXT_DOOR_METERS and b.startswith(a)
    if a in b or b in a or SequenceMatcher(None, a, b).ratio() >= 0.6:
        return True
    parts = [p for p in a.split("앤") if len(p) >= 3]
    return (len(parts) > 1 and distance is not None and distance <= JOINED_BRAND_METERS
            and any(b.startswith(p) for p in parts))


def same_store(target: InvestigationTarget, name: str, road_address: str, distance: float | None = None) -> bool:
    """지도 후보가 DB 주소에 있는 이 가게인가 — 상호가 비슷하고 같은 동네.

    `distance` 는 DB 좌표에서 후보까지의 거리(m, 카카오만 준다). 바로 옆이면 짧은 상호·두 상호를 이은 이름도 맞춘다.
    """
    if road_address and target.address and _region(road_address) != _region(target.address):
        return False
    if road_address and target.address and not _same_locality(road_address, target.address):
        return False
    return _similar_name(target, name, distance)


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
            # 네이버 검색 API 는 장소 링크를 주지 않는다 — 담당자가 누를 지도 검색 링크를 만든다. 링크에도 네이버 값을
            # 담지 않는다(저장 금지) — 우리 DB 의 상호·주소로 검색한다.
            url = "https://map.naver.com/p/search/" + quote(f"{name} {target.address or ''}".strip())
            out = [_observe(ChangeField.NAME, title, "map.naver.com", url, storable=False, label="네이버 지도")]
            if road:
                out.append(_observe(ChangeField.ADDRESS, road, "map.naver.com", url, storable=False, label="네이버 지도"))
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
                distance = float(place["distance"]) if place.get("distance") else None
                if same_store(target, place["place_name"], place.get("road_address_name", ""), distance):
                    # 바로 옆이라서만 같은 가게로 본 경우("은정" ↔ "은정식당", 이은 두 상호 중 하나)는 이름이 원래 다르게
                    # 적혀 있는 것이다 — 그 이름으로 "가게명 다름"을 내지 않는다. 주소·전화 비교에는 쓴다.
                    same_name = same_store(target, place["place_name"], place.get("road_address_name", ""))
                    return self._observations(place, "카카오맵", with_name=same_name)
        return []

    @staticmethod
    def _observations(place: dict, label: str, *, with_name: bool = True) -> list[Observation]:
        url = place.get("place_url") or "https://map.kakao.com"
        out = []
        if with_name:
            out.append(_observe(ChangeField.NAME, place["place_name"], "map.kakao.com", url, storable=False, label=label))
        if place.get("road_address_name"):
            out.append(_observe(ChangeField.ADDRESS, place["road_address_name"], "map.kakao.com", url,
                                storable=False, label=label))
        if place.get("phone"):
            out.append(_observe(ChangeField.PHONE, place["phone"], "map.kakao.com", url, storable=False, label=label))
        return out


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
