"""지도 대조 — 지도에 등록된 값을 관측으로 가져오고, 지도로 끝나면 웹검색을 부르지 않는다."""

from __future__ import annotations

import httpx
import pytest

from src.backend_client.models import StoreStatus
from src.investigation.classify import classify, coverage
from src.investigation.map_lookup import KakaoLocalLookup, MapLookup, NaverLocalLookup, same_store
from src.investigation.models import ChangeField, InvestigationTarget
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

    def test_두_글자_이하_상호는_정확히_같아야_한다(self):
        e1 = TARGET.model_copy(update={"name": "E1(이원)"})

        assert not same_store(e1, "SK에너지 E1충전소", "대구광역시 서구 국채보상로 1")


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

    def test_네이버_지역_검색은_상호와_주소를_관측으로_준다(self):
        payload = {"items": [{"title": "<b>빠레뜨치킨</b> 비산점", "roadAddress": "대구광역시 서구 국채보상로67길 38"}]}

        observations = NaverLocalLookup(http=self._http(payload)).observe(TARGET)

        assert [(o.field, o.storable) for o in observations] == [(ChangeField.NAME, True), (ChangeField.ADDRESS, True)]
        assert observations[0].value == "빠레뜨치킨 비산점"
        assert observations[0].sources[0].url.startswith("https://map.naver.com/p/search/")

    def test_카카오_값은_저장할_수_없는_관측이다(self):
        payload = {"documents": [{"place_name": "빠레뜨치킨 비산점", "road_address_name": "대구 서구 국채보상로67길 38",
                                  "phone": "053-567-3050", "place_url": "http://place.map.kakao.com/1"}]}

        observations = KakaoLocalLookup(api_key="k", http=self._http(payload)).observe(TARGET)

        assert {o.field for o in observations} == {ChangeField.NAME, ChangeField.ADDRESS, ChangeField.PHONE}
        assert not any(o.storable for o in observations)
        assert all("053-567-3050" not in o.evidence for o in observations)

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


class StubAgent:
    def __init__(self) -> None:
        self.calls = []

    def continue_from(self, target, maps):
        self.calls.append(maps)
        return classify(target, ResearchResult(maps))


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
