"""2차 조사 수집 단계 — 모델 응답 파싱, 그라운딩 출처 연결, 조사 대상 선별."""

from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest

from src.investigation.models import (
    ChangeField,
    InvestigationTarget,
    PlaceCheck,
    PlaceStatus,
)
from src.investigation.web import WebInvestigator
from src.investigation.web_research import (
    LLM_TIMEOUT_SECONDS,
    MockResearchProvider,
    ResearchParseError,
    ResearchResult,
    ResearchTimeout,
    ResearchUngrounded,
    Source,
    Support,
    VertexResearchProvider,
    build_research_prompt,
    normalize_date,
    grounding_of,
    parse_observations,
    resolve_redirect,
)

TARGET = InvestigationTarget(store_id=1, name="예시분식", address="대구 중구 동성로 12")


class TestParseObservations:
    def test_한_줄을_관측_하나로_옮긴다(self):
        [o] = parse_observations("address | 대구 북구 대학로 5 | 이전 안내문이 붙어 있다 | 2026-09-01")

        assert (o.field, o.value, o.evidence, o.observed_at, o.sources) == (
            ChangeField.ADDRESS,
            "대구 북구 대학로 5",
            "이전 안내문이 붙어 있다",
            "2026-09-01",
            (),
        )

    def test_날짜_칸은_없어도_된다(self):
        [o] = parse_observations("phone | 053-111-2222 | 전화번호는 053-111-2222")

        assert o.observed_at == ""

    def test_목록_기호와_앞뒤_문장은_무시한다(self):
        text = "확인 결과입니다.\n- status | CLOSED | 폐업했다 |\n1. name | 예시김밥 | 간판이 바뀌었다 |\n끝."

        assert [o.field for o in parse_observations(text)] == [ChangeField.STATUS, ChangeField.NAME]

    @pytest.mark.parametrize(
        "bad",
        ["menu | 김밥 | 메뉴판", "status | CLOSED | ", "status | 영업 종료 | 21:20에 영업 종료", "항목 | 값 | 근거 문장 | 날짜"],
        ids=["모르는_항목", "근거_없음", "상태값_이탈", "형식_설명_줄"],
    )
    def test_약속을_어긴_줄만_뺀다(self, bad):
        parsed = parse_observations(bad + "\nstatus | CLOSED | 폐업했다")

        assert len(parsed) == 1

    def test_상태값은_대소문자를_가리지_않는다(self):
        [o] = parse_observations("status | suspended | 휴업 중이다")

        assert o.value == "SUSPENDED"

    @pytest.mark.parametrize("text", ["없음", " 없음. ", '"없음"'])
    def test_찾은_것이_없으면_빈_목록(self, text):
        assert parse_observations(text) == []

    def test_항목마다_없음이라고_적어도_찾은_것이_없는_것이다(self):
        """실측: '없음' 한 단어 대신 항목별로 적어 왔다."""
        text = "status | 없음\nphone | 없음 |\naddress | 없음 | |\nname | 없음"

        assert parse_observations(text) == []

    @pytest.mark.parametrize("value", ["없음", "정보 없음", "-", "N/A", "(모름)"])
    def test_값_자리의_빈_표시는_관측이_아니다(self, value):
        """실측(크랩포차): 'phone | 없음 | 전화없음.' 을 새 전화번호 '없음' 으로 읽었다."""
        text = f"phone | {value} | 전화없음.\nstatus | CLOSED | 폐점."

        assert [o.field for o in parse_observations(text)] == [ChangeField.STATUS]

    @pytest.mark.parametrize(
        ("written", "status"),
        [("폐업", "CLOSED"), ("폐점", "CLOSED"), ("임시 휴업", "SUSPENDED"), ("영업 중", "OPEN"), ("closed", "CLOSED")],
    )
    def test_상태를_한글로_적어도_읽는다(self, written, status):
        """한글로 적은 줄을 버리면 폐업 신호를 잃고, 그 줄뿐이면 형식 이탈로 조사가 실패한다."""
        [observation] = parse_observations(f"status | {written} | 근거 |")

        assert observation.value == status

    @pytest.mark.parametrize("written", ["영업 종료", "휴무", "모름"])
    def test_뜻이_하나로_정해지지_않는_상태는_받지_않는다(self, written):
        """'21:20에 영업 종료'(마감 시각), '휴무'(정기 휴일)를 폐업·휴업으로 읽으면 안 된다."""
        [observation] = parse_observations(f"status | {written} | 근거 |\nphone | 053-111-2222 | 근거 |")

        assert observation.field is ChangeField.PHONE

    def test_머리줄_아래_없음만_있으면_찾은_것이_없는_것이다(self):
        """간이 DB 회귀(갑이회수산): 형식 머리줄을 따라 적고 '없음'만 남겨 형식 이탈로 두 번 실패했다."""
        assert parse_observations("항목 | 값 | 근거 문장 | 날짜\n없음") == []

    def test_없음_줄이_섞여_있어도_찾은_줄은_읽는다(self):
        assert len(parse_observations("status | 없음\nphone | 053-1 | 전화번호는 053-1")) == 1

    @pytest.mark.parametrize("text", ["검색 결과가 없습니다", '{"observations": []}', ""])
    def test_형식에_맞는_줄이_없으면_파싱_실패로_올린다(self, text):
        with pytest.raises(ResearchParseError):
            parse_observations(text)


def test_프롬프트에_비교할_DB_값을_담는다():
    prompt = build_research_prompt(
        InvestigationTarget(store_id=1, name="예시분식", phone="053-111-2222")
    )

    assert "예시분식" in prompt and "053-111-2222" in prompt and "(모름)" in prompt


def _span(raw: str, text: str) -> tuple[int, int]:
    """raw 안에서 text 의 UTF-8 바이트 구간 — 구글 support 가 쓰는 단위."""
    at = raw.index(text)
    start = len(raw[:at].encode())
    return start, start + len(text.encode())


class TestSourcesFromGrounding:
    """줄의 바이트 구간과 겹치는 support 의 검색 결과가 그 줄의 출처다."""

    CHUNKS = [Source("blog.naver.com", "r0"), Source("tistory.com", "r1"), Source("namu.wiki", "r2")]
    RAW = "status | CLOSED | 첫째 가게는 폐업했다 |\nstatus | CLOSED | 둘째 안내문에도 폐업 |"

    def test_줄과_겹치는_검색_결과를_붙인다(self):
        s1, e1 = _span(self.RAW, "첫째 가게는 폐업했다")
        s2, e2 = _span(self.RAW, "둘째 안내문에도 폐업")

        first, second = parse_observations(
            self.RAW, self.CHUNKS, [Support(s1, e1 - 3, (0,)), Support(s2 + 2, e2, (1, 2, 1))]
        )

        assert first.domains == {"blog.naver.com"}
        assert [s.domain for s in second.sources] == ["tistory.com", "namu.wiki"]  # 중복 제거

    def test_줄_끝만_덮는_구간도_그_줄의_출처다(self):
        """실측: support 가 '| 2025-08-22' 처럼 줄 끝 날짜 칸만 덮어 오는 경우가 있다."""
        _, e1 = _span(self.RAW, "첫째 가게는 폐업했다 |")

        first, second = parse_observations(self.RAW, self.CHUNKS, [Support(e1 - 2, e1, (2,))])

        assert first.domains == {"namu.wiki"} and second.sources == ()

    def test_다른_줄의_구간은_붙이지_않는다(self):
        """문자 오프셋으로 착각하면 구간이 어긋나 엉뚱한 관측에 붙는다 — 바이트여야 한다."""
        s2, e2 = _span(self.RAW, "둘째 안내문에도 폐업")

        first, second = parse_observations(self.RAW, self.CHUNKS, [Support(s2, e2, (0,))])

        assert first.sources == () and second.domains == {"blog.naver.com"}

    def test_값과_근거가_같은_줄이_이어져도_각자_자기_출처를_본다(self):
        """실측 회귀: 근거 문장을 텍스트로 찾던 때 셋째 줄 출처가 둘째 줄에 붙었다."""
        raw = "name | 본점 | 본점 |\nname | 본점 | 본점 |\nname | 본점 | 본점 |"
        spans = []
        offset = 0
        for line in raw.splitlines(keepends=True):
            spans.append((offset, offset + len(line.encode())))
            offset += len(line.encode())

        observations = parse_observations(
            raw, self.CHUNKS, [Support(a, b, (i,)) for i, (a, b) in enumerate(spans)]
        )

        assert [sorted(o.domains) for o in observations] == [["blog.naver.com"], ["tistory.com"], ["namu.wiki"]]

    def test_범위를_벗어난_청크_번호는_버린다(self):
        s1, e1 = _span(self.RAW, "첫째 가게는 폐업했다")

        first, _ = parse_observations(self.RAW, self.CHUNKS, [Support(s1, e1, (7,))])

        assert first.sources == ()

    def test_그라운딩을_안_주면_출처_없는_관측이다(self):
        assert all(o.sources == () for o in parse_observations(self.RAW))


def _grounded_response(text: str, chunks: list[tuple[str | None, str, str]], supports=()):
    return SimpleNamespace(
        text=text,
        candidates=[
            SimpleNamespace(
                grounding_metadata=SimpleNamespace(
                    grounding_chunks=[
                        SimpleNamespace(web=SimpleNamespace(domain=d, title=t, uri=u)) for d, t, u in chunks
                    ],
                    grounding_supports=[
                        SimpleNamespace(
                            segment=SimpleNamespace(start_index=s, end_index=e, text=""),
                            grounding_chunk_indices=list(idx),
                        )
                        for s, e, idx in supports
                    ],
                )
            )
        ],
    )


def test_그라운딩에서_청크와_support_를_꺼낸다():
    response = _grounded_response(
        "", [(None, "www.Tistory.com", "r0"), ("blog.naver.com", "블로그", "r1")], [(None, 10, (1,))]
    )

    chunks, supports = grounding_of(response)

    assert chunks == [Source("tistory.com", "r0"), Source("blog.naver.com", "r1")]
    assert supports == [Support(0, 10, (1,))]  # 첫 구간은 start_index 가 None 으로 온다


def test_구간이_없는_support_는_건너뛴다():
    response = _grounded_response("", [("a.com", "", "r0")], [(0, 5, (0,))])
    response.candidates[0].grounding_metadata.grounding_supports.append(
        SimpleNamespace(segment=None, grounding_chunk_indices=[0])
    )

    _, supports = grounding_of(response)

    assert supports == [Support(0, 5, (0,))]


def test_그라운딩이_없는_응답은_빈_목록():
    assert grounding_of(SimpleNamespace(candidates=[SimpleNamespace(grounding_metadata=None)])) == ([], [])


def _fake_client(response):
    calls = []
    client = SimpleNamespace(
        models=SimpleNamespace(generate_content=lambda **kw: calls.append(kw) or response)
    )
    return client, calls


def _redirects(mapping: dict[str, str]) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        location = mapping.get(str(request.url))
        return httpx.Response(302, headers={"location": location}) if location else httpx.Response(404)

    return httpx.Client(transport=httpx.MockTransport(handler))


class TestSearchQueries:
    def test_모델이_쓴_검색어를_남긴다(self):
        """판정에는 쓰지 않는다 — 검색이 흔들린 것인지 추출이 흔들린 것인지 가리는 데 쓴다."""
        response = _grounded_response("없음", [])
        response.candidates[0].grounding_metadata.web_search_queries = ["예시분식 대구", "예시분식 폐업"]
        client, _ = _fake_client(response)

        result = VertexResearchProvider(client=client, http=_redirects({})).research(TARGET)

        assert result.queries == ("예시분식 대구", "예시분식 폐업")


class TestVertexResearchProvider:
    def test_출처를_잇고_원래_URL_로_푼다(self):
        raw = "status | CLOSED | 폐업 안내문이 붙어 있다 |"
        start, end = _span(raw, "폐업 안내문이 붙어 있다")
        redirect = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"
        response = _grounded_response(raw, [("blog.naver.com", "", redirect)], [(start, end, (0,))])
        client, calls = _fake_client(response)

        result = VertexResearchProvider(
            client=client, http=_redirects({redirect: "https://blog.naver.com/real"})
        ).research(TARGET)

        [o] = result.observations
        assert o.sources == (Source("blog.naver.com", "https://blog.naver.com/real"),)
        assert calls[0]["config"].tools[0].google_search is not None

    def test_관측이_있는데_그라운딩이_비면_실패로_올린다(self):
        """그대로 두면 관측이 전부 출처 없음으로 빠져 변화없음으로 조용히 나간다."""
        client, _ = _fake_client(_grounded_response("status | CLOSED | 폐업했다 |", []))

        with pytest.raises(ResearchUngrounded):
            VertexResearchProvider(client=client, http=_redirects({})).research(TARGET)

    def test_LLM_호출에_타임아웃을_건다(self):
        client, calls = _fake_client(_grounded_response("없음", []))

        VertexResearchProvider(client=client, http=_redirects({})).research(TARGET)

        assert calls[0]["config"].http_options.timeout == int(LLM_TIMEOUT_SECONDS * 1000)

    def test_시간이_넘으면_ResearchTimeout_으로_올린다(self):
        def timing_out(**kwargs):
            raise httpx.ReadTimeout("응답 없음")

        client = SimpleNamespace(models=SimpleNamespace(generate_content=timing_out))

        with pytest.raises(ResearchTimeout):
            VertexResearchProvider(client=client, http=_redirects({})).research(TARGET)

    def test_찾은_것이_없으면_그라운딩_없이도_정상이다(self):
        client, _ = _fake_client(_grounded_response("없음", []))

        result = VertexResearchProvider(client=client, http=_redirects({})).research(TARGET)

        assert result.observations == []


def test_리다이렉트를_못_풀면_받은_주소를_그대로_쓴다():
    assert resolve_redirect("https://x.test/r", _redirects({})) == "https://x.test/r"


def test_리다이렉트를_풀다_연결이_끊겨도_받은_주소를_그대로_쓴다():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("끊김")

    http = httpx.Client(transport=httpx.MockTransport(handler))

    assert resolve_redirect("https://x.test/r", http) == "https://x.test/r"


def test_같은_리다이렉트_주소는_한_번만_푼다():
    raw = "status | CLOSED | 폐업했다 |\nname | 예시김밥 | 간판이 바뀌었다 |"
    redirect = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"
    response = _grounded_response(raw, [("blog.naver.com", "", redirect)], [(0, len(raw.encode()), (0,))])
    client, _ = _fake_client(response)
    asked: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        asked.append(str(request.url))
        return httpx.Response(302, headers={"location": "https://blog.naver.com/real"})

    result = VertexResearchProvider(client=client, http=httpx.Client(transport=httpx.MockTransport(handler))).research(
        TARGET
    )

    assert len(result.observations) == 2
    assert asked == [redirect]


class TestWebInvestigator:
    def test_타임아웃은_재시도하지_않고_그_가게만_실패로_올린다(self):
        """한 번 더 기다리면 한 건이 두 배로 늘어난다. 예외를 올리면 POST /investigations 가 건별 실패로 적는다."""

        class Slow:
            calls = 0

            def research(self, target):
                self.calls += 1
                raise ResearchTimeout("60초 초과")

        provider = Slow()
        with pytest.raises(ResearchTimeout):
            WebInvestigator(provider).investigate(TARGET)

        assert provider.calls == 1

    def test_한_번_깨져도_재시도해서_판정한다(self):
        class Flaky:
            calls = 0

            def research(self, target):
                self.calls += 1
                if self.calls == 1:
                    raise ResearchUngrounded("그라운딩 없음")
                return ResearchResult()

        provider = Flaky()
        found = WebInvestigator(provider).investigate(TARGET)

        assert provider.calls == 2 and found.classification is not None

    def test_지도_확인이_있으면_한_번_불러_결과에_담는다(self):
        class Checker:
            calls = 0

            def check(self, target):
                self.calls += 1
                return PlaceCheck(status=PlaceStatus.FOUND, place_url="u")

        checker = Checker()
        found = WebInvestigator(MockResearchProvider(), place_checker=checker).investigate(TARGET)

        assert checker.calls == 1
        assert found.map_check.status is PlaceStatus.FOUND

    def test_끝까지_깨지면_예외로_올린다(self):
        """서버가 건별 실패(`failure`)로 남긴다."""

        class Broken:
            def research(self, target):
                raise ResearchParseError("형식 이탈")

        with pytest.raises(ResearchParseError):
            WebInvestigator(Broken()).investigate(TARGET)

    def test_요청_한도에_걸리면_기다렸다_다시_부른다(self):
        class RateLimited(Exception):
            code = 429

        class Busy:
            calls = 0

            def research(self, target):
                self.calls += 1
                if self.calls <= 2:
                    raise RateLimited("429 RESOURCE_EXHAUSTED")
                return ResearchResult()

        slept = []
        provider = Busy()
        found = WebInvestigator(provider, rate_limit_waits=(1.0, 2.0), sleep=slept.append).investigate(TARGET)

        assert found.failure is None and slept == [1.0, 2.0] and provider.calls == 3

    def test_기다려도_한도에_걸리면_예외로_올린다(self):
        class RateLimited(Exception):
            code = 429

        class AlwaysBusy:
            def research(self, target):
                raise RateLimited("429")

        slept = []
        with pytest.raises(RateLimited):
            WebInvestigator(AlwaysBusy(), rate_limit_waits=(1.0,), sleep=slept.append).investigate(TARGET)
        assert slept == [1.0]

    def test_한도_말고_다른_오류는_기다리지_않는다(self):
        class Broken:
            def research(self, target):
                raise PermissionError("403")

        slept = []
        with pytest.raises(PermissionError):
            WebInvestigator(Broken(), sleep=slept.append).investigate(TARGET)
        assert slept == []

    def test_목_provider_로_조사한다(self):
        found = WebInvestigator(MockResearchProvider()).investigate(TARGET)

        assert found.store_id == 1 and found.failure is None


@pytest.mark.parametrize(
    ("written", "normalized"),
    [
        ("2026-09-01", "2026-09-01"),
        ("2025.7.22.", "2025-07-22"),
        ("2025년 7월", "2025-07"),
        ("2024", "2024"),
        ("2025-13-01", "2025"),  # 달이 아니면 연도까지만
        ("맛집검색", ""),  # 실측 — 날짜 칸에 엉뚱한 말
        ("", ""),
    ],
)
def test_날짜는_비교할_수_있는_모양으로_고친다(written, normalized):
    assert normalize_date(written) == normalized
