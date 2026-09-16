"""비즈노 후보에 **등급과 근거**를 매긴다. 채택은 하지 않는다.

계획 문서의 원칙 그대로다 — *폴백 결과를 자동으로 DB에 쓰지 않는다. 제안값 + 근거 +
미확인 상태로 담당자 큐에 올린다.*

"후보가 1건이면 채택"이 안 되는 이유는 실측 반례 때문이다. `봉궁순대국`은 후보 1건에
이름 포함관계까지 통과하는데 다른 법인이었고, `몽쁘띠`는 완전일치인데 다른 지역 가게였다.
주소로 가려야 하는데 비즈노 응답에 주소가 없다.

폐업은 거르는 조건이 아니라 **플래그**다 — `감성커피 평촌학원가점`의 정답이 폐업자였고,
애초에 폐업 탐지가 이 에이전트의 목적이다.
"""

import re
from dataclasses import dataclass
from enum import IntEnum

from src.biz_number.bizno import ACTIVE_STATUS_CODE, BiznoRecord


class Grade(IntEnum):
    """이름 근거의 강도. 값이 클수록 강하다."""

    WEAK = 0  # 검색은 걸렸지만 이름상 근거가 없다
    CANDIDATE_IN_QUERY = 1  # 검색어가 후보명을 품는다 (`세븐일레븐 우정조암점` ⊃ `세븐일레븐`)
    QUERY_IN_CANDIDATE = 2  # 후보명이 검색어를 품는다 (`봉궁순대국` ⊂ `... 봉궁순대국`)
    EXACT = 3  # 공백을 무시하면 같다


_GRADE_LABELS = {
    Grade.EXACT: "완전일치",
    Grade.QUERY_IN_CANDIDATE: "후보명이 상호명을 포함",
    Grade.CANDIDATE_IN_QUERY: "상호명이 후보명을 포함",
    Grade.WEAK: "이름 근거 없음",
}


def grade_label(grade: Grade) -> str:
    return _GRADE_LABELS[grade]


def _squash(text: str) -> str:
    """공백만 지운다 — 전각·법인격·음차 정규화는 별도 작업(계획서 2번) 몫이다."""
    return "".join(text.split())


@dataclass(frozen=True)
class NameMatch:
    record: BiznoRecord
    grade: Grade
    reasons: tuple[str, ...]
    is_closed: bool  # 계속사업자가 아님. 거르는 조건이 아니라 담당자에게 보여줄 플래그

    @property
    def label(self) -> str:
        return grade_label(self.grade)


def grade_candidate(query: str, record: BiznoRecord) -> NameMatch:
    q, company = _squash(query), _squash(record.company)
    reasons: list[str] = []

    if q and q == company:
        grade = Grade.EXACT
        reasons.append(f"상호명과 등록상호명이 공백 무시 시 동일 ({record.company})")
    elif q and q in company:
        grade = Grade.QUERY_IN_CANDIDATE
        reasons.append(f"등록상호명 안에 상호명이 들어 있음 ({record.company})")
    elif company and company in q:
        grade = Grade.CANDIDATE_IN_QUERY
        reasons.append(f"상호명 안에 등록상호명이 들어 있음 ({record.company})")
    else:
        grade = Grade.WEAK
        reasons.append(f"이름이 겹치지 않음 ({record.company})")

    is_closed = record.status_code != ACTIVE_STATUS_CODE
    if is_closed:
        reasons.append(f"사업자상태: {record.status or '알 수 없음'}")

    return NameMatch(record=record, grade=grade, reasons=tuple(reasons), is_closed=is_closed)


def grade_candidates(query: str, candidates: list[BiznoRecord]) -> list[NameMatch]:
    """후보 전체에 등급을 매겨 강한 순으로 돌려준다. 동점이면 원래 순서를 유지한다."""
    matches = [grade_candidate(query, c) for c in candidates]
    return sorted(matches, key=lambda m: m.grade, reverse=True)


@dataclass(frozen=True)
class Suggestion:
    """담당자 큐에 올릴 제안. **채택된 값이 아니다.**"""

    query: str
    best: NameMatch | None
    alternatives: tuple[NameMatch, ...]  # best와 같은 등급인 다른 후보들
    total_candidates: int

    @property
    def is_unambiguous(self) -> bool:
        """최고 등급이 `완전일치`이고 그 등급의 후보가 유일한가.

        자동 반영해도 된다는 뜻이 **아니다.** 담당자 큐에서 우선순위를 가르고
        "확인이 쉬운 건"과 "근거가 약한 건"을 나누기 위한 표시다.
        """
        return self.best is not None and self.best.grade == Grade.EXACT and not self.alternatives


def suggest(query: str, candidates: list[BiznoRecord]) -> Suggestion:
    matches = grade_candidates(query, candidates)
    if not matches:
        return Suggestion(query=query, best=None, alternatives=(), total_candidates=0)

    best = matches[0]
    tied = tuple(m for m in matches[1:] if m.grade == best.grade)
    return Suggestion(query=query, best=best, alternatives=tied, total_candidates=len(candidates))


# ── 주소 대조 ────────────────────────────────────────────────
# 비즈노 응답에는 주소가 없지만, 웹검색 근거 스니펫에는 들어 있는 경우가 있다.
# 그걸로 "다른 지점이 검색된" 케이스를 걸러낸다 (`벨라로사` → 안양 평촌의 다른 가게).

_REGION_TOKEN = re.compile(r"[가-힣]+[시군구]")


def _region_tokens(address: str) -> set[str]:
    """주소에서 시/군/구 토큰만 뽑는다."""
    return set(_REGION_TOKEN.findall(address or ""))


def address_conflicts(db_address: str, candidate_address: str) -> bool:
    """두 주소가 서로 다른 행정구역을 가리키는가.

    **휴리스틱이다.** 양쪽에서 시/군/구 토큰을 뽑아 겹치는 게 하나도 없을 때만 참을
    돌려준다. 한쪽이라도 토큰이 없으면(주소를 못 얻었거나 형식이 다르면) 거짓이다 —
    **모르는 것을 충돌로 취급하지 않는다.** 놓치는 쪽이 잘못 거르는 쪽보다 낫다.
    """
    db_tokens, candidate_tokens = _region_tokens(db_address), _region_tokens(candidate_address)
    if not db_tokens or not candidate_tokens:
        return False
    return not (db_tokens & candidate_tokens)
