"""카카오맵 확인 — 주소 → 좌표 → 반경 150m 상호 검색. 카카오 응답은 가짜로 돈다."""

from __future__ import annotations

import httpx
import pytest

from src.investigation.kakao_map import KakaoPlaceChecker, same_place_name, search_name
from src.investigation.models import InvestigationTarget, PlaceStatus

TARGET = InvestigationTarget(store_id=1, name="예시분식(본점)", address="대구광역시 중구 동성로 12")


def checker(addresses: list[dict], places: list[dict], seen: list[httpx.Request] | None = None):
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        documents = addresses if request.url.path.endswith("address.json") else places
        return httpx.Response(200, json={"documents": documents})

    return KakaoPlaceChecker("key", http=httpx.Client(transport=httpx.MockTransport(handler)))


COORD = [{"x": "128.59", "y": "35.87"}]


def test_반경_안에_같은_상호가_있으면_찾은_것이고_장소_링크만_남긴다():
    place = {"place_name": "예시분식 동성로점", "phone": "053-1", "place_url": "http://place.map.kakao.com/1"}
    found = checker(COORD, [place]).check(TARGET)

    assert found.status is PlaceStatus.FOUND
    assert found.place_url == "http://place.map.kakao.com/1"
    assert set(found.model_dump(by_alias=True)) == {"status", "placeUrl"}  # 장소명·전화·좌표는 버린다


def test_다른_이름만_있으면_못_찾은_것이다():
    found = checker(COORD, [{"place_name": "옆집김밥", "place_url": "u"}]).check(TARGET)

    assert found.status is PlaceStatus.NOT_FOUND
    assert found.place_url is None


def test_DB_좌표가_있으면_주소를_좌표로_바꾸지_않는다():
    seen: list[httpx.Request] = []
    target = InvestigationTarget(store_id=3, name="예시분식", address="대구 중구 동성로 12", lat=35.87, lng=128.59)
    checker([], [], seen).check(target)

    assert [r.url.path.rsplit("/", 1)[-1] for r in seen] == ["keyword.json"]
    assert (seen[0].url.params["x"], seen[0].url.params["y"]) == ("128.59", "35.87")


def test_좌표를_못_얻으면_좌표_실패다():
    assert checker([], []).check(TARGET).status is PlaceStatus.NO_COORDINATES


def test_주소가_없으면_부르지_않는다():
    seen: list[httpx.Request] = []
    found = checker(COORD, [], seen).check(InvestigationTarget(store_id=2, name="예시분식"))

    assert found.status is PlaceStatus.NO_COORDINATES
    assert seen == []


def test_검색어가_비는_상호면_부르지_않는다():
    seen: list[httpx.Request] = []
    found = checker(COORD, [], seen).check(InvestigationTarget(store_id=4, name="(주)", address="대구 중구 동성로 12"))

    assert found.status is PlaceStatus.UNAVAILABLE
    assert seen == []


def test_키가_없으면_확인하지_않는다():
    assert KakaoPlaceChecker("").check(TARGET).status is PlaceStatus.UNAVAILABLE


def test_호출이_실패해도_예외를_올리지_않는다():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    found = KakaoPlaceChecker("key", http=httpx.Client(transport=httpx.MockTransport(handler))).check(TARGET)

    assert found.status is PlaceStatus.UNAVAILABLE


def test_검색은_좌표_반경_150m_안에서_정리한_상호로_한다():
    seen: list[httpx.Request] = []
    checker(COORD, [], seen).check(TARGET)

    keyword = seen[1].url.params
    assert (keyword["query"], keyword["radius"]) == ("예시분식", "150")
    assert (float(keyword["x"]), float(keyword["y"])) == (128.59, 35.87)


@pytest.mark.parametrize(
    ("ours", "theirs", "same"),
    [
        ("은정", "은정식당", True),
        ("빡빡이 참숯화로구이", "빡빡이참숯화로구이 본점", True),
        ("행컵(계명대점)", "행컵 계명대점", True),
        ("주식회사 대신에프앤", "대신에프앤", True),
        ("샵도쿄시장", "도쿄시장 동성로점", False),  # 엄격하게 둔다 — 실측에서 느슨하게만 맞았다
        ("예시분식", "옆집김밥", False),
    ],
)
def test_상호_비교(ours, theirs, same):
    assert same_place_name(ours, theirs) is same


def test_검색어에서_법인_표기와_괄호를_뺀다():
    assert search_name("롯데컬처웍스(주)롯데시네마성서스위트샵(7층)") == "롯데컬처웍스 롯데시네마성서스위트샵"
