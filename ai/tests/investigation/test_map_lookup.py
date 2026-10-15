"""지도 대조 — 지도에 등록된 값을 관측으로 가져오고, 지도로 끝나면 웹검색을 부르지 않는다."""

from __future__ import annotations

from urllib.parse import unquote

import httpx
import pytest

from src.backend_client.models import StoreStatus
from src.investigation.classify import classify, coverage
from src.investigation.map_lookup import KakaoLocalLookup, MapLookup, NaverLocalLookup, same_store
from src.investigation.models import ChangeField, InvestigationTarget, PlaceCheck, PlaceStatus
from src.investigation.web import WebInvestigator
from src.investigation.web_research import Observation, ResearchResult, Source

TARGET = InvestigationTarget(
    store_id=1,
    name="빠레뜨치킨",
    address="대구광역시 서구 국채보상로67길 38",
    phone="053-567-3050",
    internal_status=StoreStatus.OPEN,
    lat=35.87,
    lng=128.56,
)


def map_obs(field: ChangeField, value: str, domain: str = "map.kakao.com", *, storable: bool = False) -> Observation:
    return Observation(field=field, value=value, evidence="지도에 등록된 값", observed_at="2026-10-06",
                       sources=(Source(domain=domain, url=f"https://{domain}/place/1"),), storable=storable)


class TestSameStore:
    @pytest.mark.parametrize(
        ("name", "road"),
        [("빠레뜨치킨 비산점", "대구광역시 서구 국채보상로67길 38"), ("빠래뜨치킨", "대구 서구 국채보상로67길 38")],
    )
    def test_상호가_비슷하고_같은_지역이면_같은_가게다(self, name, road):
        assert same_store(TARGET, name, road)

    @pytest.mark.parametrize(
        ("name", "road"),
        [
            ("풍경어린이집", "대구광역시 서구 국채보상로67길 38"),  # 같은 건물의 다른 업체
            ("빠레뜨치킨", "인천광역시 미추홀구 장천로 12"),  # 다른 지역의 같은 이름
        ],
    )
    def test_주소만_같거나_지역이_다르면_다른_가게다(self, name, road):
        assert not same_store(TARGET, name, road)

    @pytest.mark.parametrize(
        ("db_address", "name", "road"),
        [
            # 군은 넓다 — 같은 군의 다른 읍 가게(실측: 화원읍 상하이반점 ↔ 가창면 상하이반점)
            ("대구광역시 달성군 화원읍 비슬로525길 37 (평광주택상가 가동 101호)", "상하이반점", "대구광역시 달성군 가창면 가창로 1096-1"),
            # 같은 구라도 큰길이 다르면 다른 곳
            ("대구광역시 북구 칠성시장로7길 7 (칠성동1가)", "칠성식당", "대구광역시 북구 칠성남로 216"),
        ],
    )
    def test_같은_구_군이라도_동네가_다르면_다른_가게다(self, db_address, name, road):
        target = TARGET.model_copy(update={"name": name, "address": db_address})

        assert not same_store(target, name, road)

    @pytest.mark.parametrize(
        "road",
        [
            "대구광역시 서구 국채보상로67길 40",  # 같은 골목의 다른 건물번호 — 주소 변화로 잡아야 한다
            "대구광역시 서구 국채보상로 210",  # 같은 큰길에서 갈라진 골목
        ],
    )
    def test_큰길이_같으면_건물번호가_달라도_같은_가게다(self, road):
        assert same_store(TARGET, "빠레뜨치킨", road)

    @pytest.mark.parametrize(
        ("ours", "theirs", "distance"),
        [
            ("행컵(계명대점)", "행컵 계명대점", None),  # 짧은 상호 + 괄호 속 지점명까지 같음
            ("은정", "은정식당", 14),  # 짧은 상호라도 바로 옆이면
            ("본죽앤비빔밥(달서구청점)", "본죽&비빔밥cafe 대구달서구청점", None),  # & ↔ 앤
            ("네네치킨앤봉구스밥버거(신암동점)", "네네치킨 신암동점", 22),  # 이은 두 상호 중 하나, 바로 옆
        ],
    )
    def test_표기가_달라도_같은_가게로_본다(self, ours, theirs, distance):
        target = TARGET.model_copy(update={"name": ours})

        assert same_store(target, theirs, "대구광역시 서구 국채보상로67길 38", distance)

    @pytest.mark.parametrize(
        ("ours", "theirs", "distance"),
        [
            ("은정", "은정식당", 80),  # 짧은 상호 포함은 바로 옆일 때만
            ("네네치킨앤봉구스밥버거(신암동점)", "네네치킨 신암동점", 90),
            ("행컵(계명대점)", "행컵 경북대점", None),  # 지점명이 다르면 다른 가게
        ],
    )
    def test_조금_떨어지거나_지점이_다르면_같은_가게로_보지_않는다(self, ours, theirs, distance):
        target = TARGET.model_copy(update={"name": ours})

        assert not same_store(target, theirs, "대구광역시 서구 국채보상로67길 38", distance)

    def test_두_글자_이하_상호는_정확히_같아야_한다(self):
        e1 = TARGET.model_copy(update={"name": "E1(이원)"})

        assert not same_store(e1, "SK에너지 E1충전소", "대구광역시 서구 국채보상로 1")
        assert not same_store(e1, "SK에너지 E1충전소", "대구광역시 서구 국채보상로 1", 5)  # 바로 옆이라도 앞부분이 달라서


class TestStorable:
    """카카오 값은 저장할 수 없다 — 판정에는 쓰고 수정안에는 값 대신 근거·링크만 남긴다."""

    def test_카카오만_다른_값을_가리키면_수정안_없이_Signal_만_남는다(self):
        found = classify(TARGET, ResearchResult([map_obs(ChangeField.PHONE, "053-567-3080")]))

        assert found.proposed_changes == {}
        [signal] = found.signals
        assert signal.field is ChangeField.PHONE
        assert "053-567-3080" not in signal.evidence_text
        assert signal.evidence_url == "https://map.kakao.com/place/1"

    def test_저장할_수_있는_출처가_같은_값이면_그_값을_수정안에_쓴다(self):
        web = Observation(field=ChangeField.PHONE, value="053-567-3080", evidence="전화 053-567-3080",
                          sources=(Source(domain="blog.naver.com", url="https://blog.naver.com/p"),))
        found = classify(TARGET, ResearchResult([map_obs(ChangeField.PHONE, "053-567-3080"), web]))

        assert found.proposed_changes == {"phone": "053-567-3080"}
        assert found.signals[0].evidence_url == "https://blog.naver.com/p"


class TestCoverage:
    def test_DB_값을_전부_확인하면_confirmed(self):
        observations = [map_obs(ChangeField.NAME, "빠레뜨치킨 비산점"), map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 38"),
                        map_obs(ChangeField.PHONE, "053-567-3050")]

        assert coverage(TARGET, observations) == "confirmed"

    def test_전화를_확인_못_하면_unresolved(self):
        observations = [map_obs(ChangeField.NAME, "빠레뜨치킨"), map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 38")]

        assert coverage(TARGET, observations) == "unresolved"

    def test_다른_값이_있으면_changed(self):
        assert coverage(TARGET, [map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 40")]) == "changed"


class Research:
    def __init__(self, result: ResearchResult | None = None) -> None:
        self.calls, self.result = 0, result or ResearchResult()

    def research(self, target):
        self.calls += 1
        return self.result


class Maps:
    def __init__(self, observations: list[Observation]) -> None:
        self.observations = observations

    def observe(self, target):
        return self.observations


class TestMapsFirst:
    def test_지도로_전부_확인되면_웹검색을_부르지_않는다(self):
        research = Research()
        maps = Maps([map_obs(ChangeField.NAME, "빠레뜨치킨"), map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 38"),
                     map_obs(ChangeField.PHONE, "053-567-3050")])

        found = WebInvestigator(research, map_lookup=maps).investigate(TARGET)

        assert research.calls == 0 and found.signals == []

    def test_지도로_안_끝나면_웹검색을_더한다(self):
        web = Observation(field=ChangeField.PHONE, value="053-567-9999", evidence="전화 053-567-9999",
                          sources=(Source(domain="a.com", url="https://a.com"),))
        research = Research(ResearchResult([web]))
        maps = Maps([map_obs(ChangeField.NAME, "빠레뜨치킨")])

        found = WebInvestigator(research, map_lookup=maps).investigate(TARGET)

        assert research.calls == 1 and found.proposed_changes == {"phone": "053-567-9999"}

    def test_지도_대조가_없으면_지금처럼_웹검색만_한다(self):
        research = Research()

        WebInvestigator(research).investigate(TARGET)

        assert research.calls == 1


class TestLookups:
    def _http(self, payload: dict) -> httpx.Client:
        return httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json=payload)))

    def test_네이버_지역_검색은_상호와_주소를_저장할_수_없는_관측으로_준다(self):
        payload = {"items": [{"title": "<b>빠레뜨치킨</b> 비산점", "roadAddress": "대구광역시 서구 국채보상로67길 40"}]}

        observations = NaverLocalLookup(http=self._http(payload)).observe(TARGET)

        assert [(o.field, o.storable) for o in observations] == [(ChangeField.NAME, False), (ChangeField.ADDRESS, False)]
        assert observations[0].value == "빠레뜨치킨 비산점"
        # 근거 문구·링크에는 네이버 값이 들어가지 않는다 — 링크는 DB 상호·주소로 검색한다(저장 금지).
        url = unquote(observations[0].sources[0].url)
        assert url.startswith("https://map.naver.com/p/search/") and "비산점" not in url and "67길 38" in url
        assert all("비산점" not in o.evidence and "67길 40" not in o.evidence for o in observations)

    def test_네이버에만_다른_값이면_수정안_없이_신호만_남는다(self):
        payload = {"items": [{"title": "빠레뜨치킨", "roadAddress": "대구광역시 서구 국채보상로67길 40"}]}

        found = classify(TARGET, ResearchResult(NaverLocalLookup(http=self._http(payload)).observe(TARGET)))

        assert found.proposed_changes == {} and [s.field for s in found.signals] == [ChangeField.ADDRESS]
        assert "67길 40" not in found.model_dump_json(by_alias=True)

    def test_카카오_값은_저장할_수_없는_관측이다(self):
        payload = {"documents": [{"place_name": "빠레뜨치킨 비산점", "road_address_name": "대구 서구 국채보상로67길 38",
                                  "phone": "053-567-3050", "place_url": "http://place.map.kakao.com/1"}]}

        observations = KakaoLocalLookup(api_key="k", http=self._http(payload)).observe(TARGET)

        assert {o.field for o in observations} == {ChangeField.NAME, ChangeField.ADDRESS, ChangeField.PHONE}
        assert not any(o.storable for o in observations)
        assert all("053-567-3050" not in o.evidence for o in observations)

    @staticmethod
    def _kakao(nearby: list[dict], anywhere: list[dict]) -> tuple[KakaoLocalLookup, list[dict]]:
        """반경 검색(radius 있음)에는 `nearby`, 반경 없는 검색에는 `anywhere` 를 준다. 받은 요청 인자를 기록한다."""
        calls = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(dict(request.url.params))
            documents = nearby if "radius" in request.url.params else anywhere
            return httpx.Response(200, json={"documents": documents})

        return KakaoLocalLookup(api_key="k", http=httpx.Client(transport=httpx.MockTransport(handler))), calls

    def test_바로_옆이라서만_같은_가게로_본_후보의_이름으로는_가게명_다름을_내지_않는다(self):
        eunjeong = TARGET.model_copy(update={"name": "은정"})
        place = {"place_name": "은정식당", "road_address_name": "대구 서구 국채보상로67길 38", "phone": "053-567-3050",
                 "distance": "14"}
        lookup, _ = self._kakao(nearby=[place], anywhere=[])

        observations = lookup.observe(eunjeong)

        assert {o.field for o in observations} == {ChangeField.ADDRESS, ChangeField.PHONE}
        assert classify(eunjeong, ResearchResult(observations)).signals == []

    def test_한_출처가_실패해도_나머지는_쓴다(self):
        class Broken:
            def observe(self, target):
                raise httpx.ConnectError("끊김")

        observations = MapLookup([Broken(), Maps([map_obs(ChangeField.NAME, "빠레뜨치킨")])]).observe(TARGET)

        assert len(observations) == 1

    def test_스위치가_꺼져_있으면_지도_대조를_만들지_않는다(self, monkeypatch):
        monkeypatch.delenv("NAVER_LOCAL_ENABLED", raising=False)
        monkeypatch.delenv("KAKAO_COMPARE_ENABLED", raising=False)

        assert MapLookup.from_env() is None


class Checker:
    def __init__(self, result: PlaceCheck) -> None:
        self.result, self.calls = result, 0

    def check(self, target):
        self.calls += 1
        return self.result


class StubAgent:
    def __init__(self) -> None:
        self.calls = []

    def continue_from(self, target, maps, *, place=None):
        self.calls.append(maps)
        self.place = place
        return classify(target, ResearchResult(maps), place)


class TestHybrid:
    """혼합 방식 — 지도로 확정되면 규칙으로 끝내고, 아니면 에이전트가 이어서 조사한다."""

    CONFIRMED = [map_obs(ChangeField.NAME, "빠레뜨치킨"), map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 38"),
                 map_obs(ChangeField.PHONE, "053-567-3050")]

    def test_지도로_전부_확인되면_에이전트를_부르지_않는다(self):
        agent = StubAgent()

        WebInvestigator(Research(), map_lookup=Maps(self.CONFIRMED), agent=agent).investigate(TARGET)

        assert agent.calls == []

    def test_저장할_수_있는_값으로_변화가_확인되면_에이전트를_부르지_않는다(self):
        agent = StubAgent()
        maps = [map_obs(ChangeField.ADDRESS, "대구 서구 국채보상로67길 40", "map.naver.com", storable=True)]

        WebInvestigator(Research(), map_lookup=Maps(maps), agent=agent).investigate(TARGET)

        assert agent.calls == []

    def test_카카오에만_다른_값이면_에이전트가_이어서_조사한다(self):
        agent, research = StubAgent(), Research()

        WebInvestigator(research, map_lookup=Maps([map_obs(ChangeField.PHONE, "053-567-3080")]), agent=agent).investigate(TARGET)

        assert len(agent.calls) == 1 and research.calls == 0

    def test_에이전트가_없으면_카카오에만_다른_값도_지도로_끝낸다(self):
        research = Research()

        WebInvestigator(research, map_lookup=Maps([map_obs(ChangeField.PHONE, "053-567-3080")])).investigate(TARGET)

        assert research.calls == 0

    def test_카카오맵_확인_결과를_에이전트에_넘긴다(self):
        agent = StubAgent()
        checker = Checker(PlaceCheck(status=PlaceStatus.NOT_FOUND))

        found = WebInvestigator(Research(), place_checker=checker, map_lookup=Maps([]), agent=agent).investigate(TARGET)

        assert checker.calls == 1 and agent.place == checker.result and found.map_check == checker.result

    def test_지도_대조가_꺼져_있으면_에이전트가_아니라_웹검색으로_간다(self):
        agent, research = StubAgent(), Research()

        WebInvestigator(research, agent=agent).investigate(TARGET)

        assert agent.calls == [] and research.calls == 1
