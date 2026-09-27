"""등급 판정 규칙 테스트 — 네트워크를 타지 않는다.

케이스는 2026-09-15 비즈노 실측에서 실제로 나온 것들이다
(`docs/웹검색_폴백.md` 참고).
"""

from src.biz_number.lookup import BiznoRecord
from src.biz_number.matching import Grade, grade_candidate, suggest


def record(company: str, bizno: str = "000-00-00000", status_code: str = "01", status: str = "계속사업자"):
    return BiznoRecord(
        company=company,
        bizno=bizno,
        corp_no="",
        status=status,
        status_code=status_code,
        tax_type="",
        closed_date="",
    )


def test_exact_ignores_whitespace():
    match = grade_candidate("크랩포차 철산점", record("크랩포차철산점"))
    assert match.grade is Grade.EXACT


def test_query_inside_candidate():
    """`봉궁순대국`이 후보명 안에 들어 있다 — 포함관계는 통과하지만 완전일치는 아니다."""
    match = grade_candidate("봉궁순대국", record("농업회사법인(주)선진식품지점 봉궁순대국"))
    assert match.grade is Grade.QUERY_IN_CANDIDATE


def test_candidate_inside_query():
    match = grade_candidate("세븐일레븐 우정조암점", record("세븐일레븐"))
    assert match.grade is Grade.CANDIDATE_IN_QUERY


def test_no_overlap_is_weak():
    match = grade_candidate("벨라로사", record("주식회사 우리요리"))
    assert match.grade is Grade.WEAK


def test_closed_is_flagged_not_filtered():
    """폐업자를 거르지 않는다 — `감성커피 평촌학원가점`의 정답이 폐업자다."""
    match = grade_candidate("감성커피 평촌학원가점", record("감성커피 평촌학원가점", status_code="03", status="폐업자"))

    assert match.grade is Grade.EXACT  # 등급은 그대로
    assert match.is_closed is True
    assert any("폐업자" in r for r in match.reasons)


def test_bonggung_single_candidate_is_not_unambiguous():
    """후보가 1건이어도 완전일치가 아니면 `확인 쉬움`으로 분류하지 않는다.

    이 케이스는 후보 1건 + 포함관계 통과인데 실제로는 다른 법인의 번호다 (오염 사례).
    """
    s = suggest("봉궁순대국", [record("농업회사법인(주)선진식품지점 봉궁순대국", status_code="03", status="폐업자")])

    assert s.total_candidates == 1
    assert s.best.grade is Grade.QUERY_IN_CANDIDATE
    assert s.is_unambiguous is False


def test_exact_single_candidate_is_unambiguous():
    s = suggest("치킨아메리카Z 평촌점", [record("치킨아메리카Z 평촌점")])

    assert s.is_unambiguous is True
    assert s.alternatives == ()


def test_tied_exact_candidates_are_not_unambiguous():
    """동명이인이 여럿이면 이름만으로는 못 고른다 — 주소가 없어 가를 수단이 없다."""
    s = suggest("벨라로사", [record("벨라로사", "129-31-56380"), record("벨라로사", "570-33-00977")])

    assert s.best.grade is Grade.EXACT
    assert len(s.alternatives) == 1
    assert s.is_unambiguous is False


def test_no_candidates():
    s = suggest("존재하지않는가게", [])

    assert s.best is None
    assert s.total_candidates == 0
    assert s.is_unambiguous is False


def test_candidates_sorted_by_grade():
    from src.biz_number.matching import grade_candidates

    graded = grade_candidates("곰커피", [record("전혀다른이름"), record("곰커피"), record("곰커피강남점")])

    assert [m.grade for m in graded] == [Grade.EXACT, Grade.QUERY_IN_CANDIDATE, Grade.WEAK]


# ── 주소 대조 ────────────────────────────────────────────────

import pytest

from src.biz_number.matching import address_conflicts


@pytest.mark.parametrize(
    ("db", "candidate", "expected"),
    [
        # 실측 사례 — 시흥시 가게인데 안양시 벨라로사가 검색됐다
        ("경기도 시흥시 중심상가4길 24-1", "안양시 동안구 시민대로408 4F", True),
        # 같은 시라면 충돌 아님
        ("경기도 화성시 동탄기흥로 393-20", "경기도 화성시 동탄기흥로 393-20 동탄역 파라곤", False),
        # 주소를 못 얻은 경우 — 모르는 것을 충돌로 취급하지 않는다
        ("경기도 시흥시 중심상가4길 24-1", "", False),
        ("", "안양시 동안구 시민대로408", False),
        # 행정구역 토큰이 없는 문자열
        ("경기도 시흥시 중심상가4길", "4층 A동", False),
    ],
)
def test_address_conflicts(db, candidate, expected):
    assert address_conflicts(db, candidate) is expected
