"""`resolve()` 파이프라인 테스트 — 네트워크도 LLM도 타지 않는다.

가짜 provider와 가짜 `BiznoLookup`으로 **조사 순서**만 본다.
등급 규칙은 `test_matching.py`, 응답 파싱은 `test_bizno.py`.
"""

import pytest

from src.biz_number.lookup import BiznoRecord
from src.biz_number.resolve import LLM_ATTEMPTS, resolve
from src.biz_number.web_search import CandidateParseError, NameCandidate


def record(company: str, bizno: str = "111-11-11111") -> BiznoRecord:
    return BiznoRecord(
        company=company,
        bizno=bizno,
        corp_no="",
        status="계속사업자",
        status_code="01",
        tax_type="",
        closed_date="",
    )


class FakeProvider:
    """후보를 그대로 돌려준다. `raises`를 주면 그 횟수만큼 먼저 실패한다."""

    def __init__(self, candidates: list[NameCandidate], raises: list[Exception] | None = None):
        self._candidates = candidates
        self._raises = list(raises or [])
        self.calls = 0

    def search_name_candidates(self, store_name: str, address: str) -> list[NameCandidate]:
        self.calls += 1
        if self._raises:
            raise self._raises.pop(0)
        return self._candidates


class FakeBizno:
    """호출을 기록하는 `BiznoLookup`. `by_name`/`by_bizno`에 없으면 빈 결과."""

    def __init__(self, by_name=None, by_bizno=None, complete=True):
        self._by_name = by_name or {}
        self._by_bizno = by_bizno or {}
        self._complete = complete
        self.name_queries: list[str] = []
        self.bizno_queries: list[str] = []

    def lookup_by_bizno(self, bizno: str):
        self.bizno_queries.append(bizno)
        return self._by_bizno.get(bizno)

    def search_by_name(self, name: str):
        self.name_queries.append(name)
        return self._by_name.get(name, []), self._complete


def candidate(name: str, address: str = "", biz_no: str = "") -> NameCandidate:
    return NameCandidate(
        name=name, evidence="근거", source_url="http://example.test", address=address, biz_no=biz_no
    )


def test_다른_행정구역_후보는_조회하지_않고_기각한다():
    bizno = FakeBizno()
    r = resolve("벨라로사", "성남시 분당구", FakeProvider([candidate("벨라로사", "안양시 동안구")]), bizno)

    assert r.rejected_by_address == 1
    assert bizno.name_queries == []  # 조회조차 하지 않는다
    assert r.pool == []


def test_번호가_있으면_역조회하고_이름검색을_건너뛴다():
    """번호는 유일하게 식별된다 — 이름 검색은 동명이인이 섞인다."""
    found = record("주식회사 우리요리", "207-81-43555")
    bizno = FakeBizno(by_bizno={"207-81-43555": found})

    r = resolve("벨라로사", "", FakeProvider([candidate("벨라로사", biz_no="207-81-43555")]), bizno)

    assert r.pool == [found]
    assert bizno.name_queries == []


def test_번호_역조회가_비면_이름검색으로_넘어간다():
    """없는 번호를 모델이 지어낼 수 있다. 그때 후보를 통째로 버리지 않는다."""
    found = record("주식회사 우리요리")
    bizno = FakeBizno(by_name={"벨라로사": [found]}, by_bizno={})

    r = resolve("벨라로사", "", FakeProvider([candidate("벨라로사", biz_no="000-00-00000")]), bizno)

    assert bizno.bizno_queries == ["000-00-00000"]
    assert bizno.name_queries == ["벨라로사"]
    assert r.pool == [found]


def test_형식이_깨진_번호는_역조회하지_않는다():
    """웹 페이지가 뒷자리를 가려놓는 경우가 있다 (`495-86-0****`)."""
    bizno = FakeBizno()
    resolve("가게", "", FakeProvider([candidate("가게", biz_no="495-86-0****")]), bizno)

    assert bizno.bizno_queries == []
    assert bizno.name_queries == ["가게"]


def test_후보를_먼저_찾아도_멈추지_않는다():
    """어느 게 맞는지 모르므로 후보를 끝까지 전부 조회한다."""
    a, b = record("가게A"), record("가게B")
    bizno = FakeBizno(by_name={"가게A": [a], "가게B": [b]})

    r = resolve("가게", "", FakeProvider([candidate("가게A"), candidate("가게B")]), bizno)

    assert r.pool == [a, b]
    assert r.candidate_count == 2


def test_잘린_검색을_집계한다():
    """"후보에 없다"와 "더 있는데 못 봤다"는 다르다."""
    bizno = FakeBizno(by_name={"흔한이름": [record("흔한이름")]}, complete=False)

    r = resolve("흔한이름", "", FakeProvider([candidate("흔한이름")]), bizno)

    assert r.truncated_lookups == 1


def test_후보가_0건이면_비즈노를_부르지_않는다():
    bizno = FakeBizno()
    r = resolve("가게", "", FakeProvider([]), bizno)

    assert not r.failed  # 실패가 아니라 정상 0건이다
    assert r.candidate_count == 0
    assert bizno.name_queries == []
    assert r.suggestion.best is None


def test_후보생성이_모두_실패하면_failed다():
    bizno = FakeBizno()
    provider = FakeProvider([], raises=[CandidateParseError("깨짐")] * LLM_ATTEMPTS)

    r = resolve("가게", "", provider, bizno)

    assert r.failed
    assert r.fetch.parse_failures == LLM_ATTEMPTS
    assert r.fetch.failure.startswith("파싱:")
    assert bizno.name_queries == []  # 후보가 없으니 조회할 것도 없다


def test_재시도로_살아나도_실패_횟수는_남는다():
    """재시도가 가려준 실패를 집계에서 지우지 않는다."""
    found = record("가게")
    bizno = FakeBizno(by_name={"가게": [found]})
    provider = FakeProvider([candidate("가게")], raises=[CandidateParseError("1차 깨짐")])

    r = resolve("가게", "", provider, bizno)

    assert not r.failed
    assert r.fetch.parse_failures == 1
    assert provider.calls == 2
    assert r.pool == [found]


@pytest.mark.parametrize("attempts", [LLM_ATTEMPTS])
def test_재시도_횟수를_넘기지_않는다(attempts):
    provider = FakeProvider([], raises=[CandidateParseError("깨짐")] * (attempts + 5))

    resolve("가게", "", provider, FakeBizno())

    assert provider.calls == attempts
