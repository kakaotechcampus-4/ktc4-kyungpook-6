"""가게 한 건을 조사해 사업자등록번호 **제안**을 만든다 — 파이프라인 본체.

    이름 후보 생성(LLM) → 주소가 다른 지역이면 기각
                        → 번호가 있으면 역조회 / 아니면 이름 검색
                        → 등급 판정 (채택은 하지 않는다 — `matching.py`)

정답지도 화면도 저장소도 모른다. 측정(`eval/biz_number.py`)과 운영(워커·FastAPI)이
**같은 함수**를 불러야 측정한 도달률이 운영 성능이라는 보장이 생긴다.

`print`를 넣지 말 것 — 진행 상황은 여러 건을 도는 쪽이 콜백으로 받는다.
"""

import sys
from dataclasses import dataclass

from src.biz_number.lookup import BiznoLookup, BiznoRecord, is_well_formed
from src.biz_number.matching import Suggestion, address_conflicts, suggest
from src.biz_number.web_search import CandidateParseError, CandidateProvider, NameCandidate

# 후보 생성 재시도 횟수. 그라운딩 검색을 켜면 JSON 스키마를 강제할 수 없어
# 형식 이탈이 구조적으로 가능하다 (`web_search.py`).
LLM_ATTEMPTS = 2


def _vendor_api_errors() -> tuple[type[BaseException], ...]:
    """벤더 SDK의 API 예외. 로드돼 있을 때만 잡을 수 있으므로 sys.modules를 본다."""
    genai_errors = sys.modules.get("google.genai.errors")
    return (genai_errors.APIError,) if genai_errors is not None else ()


@dataclass(frozen=True)
class FetchResult:
    """후보 생성 한 건의 결과. **시도 단위 실패를 함께 들고 온다.**

    성공/실패만 돌려주면 재시도가 가려준 실패가 집계에서 사라져, 형식 강제를 포기한
    대가가 얼마인지 알 수 없게 된다.
    """

    candidates: list[NameCandidate] | None  # None이면 모든 시도가 실패했다
    failure: str  # 마지막 실패 사유. 성공이면 빈 문자열
    parse_failures: int = 0  # 이 건에서 파싱이 깨진 시도 수
    api_failures: int = 0  # 이 건에서 벤더 API가 실패한 시도 수


def fetch_candidates(provider: CandidateProvider, name: str, address: str) -> FetchResult:
    """후보를 받아온다. 실패해도 **무엇이 몇 번 깨졌는지** 함께 돌려준다.

    파싱 실패와 벤더 API 에러(레이트리밋·인증·쿼터)를 나눠 센다 — 형식 문제와 인프라
    문제를 같은 지표로 세면 안 된다. 예외는 삼키고 돌려준다: 한 건이 터졌다고 나머지
    가게 조사가 멈추면 안 된다.
    """
    api_errors = _vendor_api_errors()
    last_error = ""
    parse_failures = api_failures = 0
    for _ in range(LLM_ATTEMPTS):
        try:
            candidates = provider.search_name_candidates(name, address)
        except CandidateParseError as e:
            parse_failures += 1
            last_error = f"파싱:{e}"
            continue
        except api_errors as e:  # noqa: B030 - 런타임에 결정되는 예외 튜플
            api_failures += 1
            last_error = f"API:{type(e).__name__}: {e}"
            continue
        return FetchResult(candidates, "", parse_failures, api_failures)
    return FetchResult(None, last_error, parse_failures, api_failures)


@dataclass(frozen=True)
class Resolution:
    """가게 한 건의 조사 결과. **채택된 값이 아니라 제안이다.**"""

    suggestion: Suggestion | None  # 후보 생성이 실패하면 None
    pool: list[BiznoRecord]  # 비즈노에서 모은 후보 전체 (등급 판정의 입력)
    candidate_count: int  # 모델이 내놓은 이름 후보 수
    rejected_by_address: int  # 다른 행정구역이라 조회하지 않은 후보 수
    truncated_lookups: int  # 페이지 상한에 걸려 잘린 조회 수
    fetch: FetchResult  # 후보 생성 단계의 성패와 실패 사유

    @property
    def failed(self) -> bool:
        """후보를 한 번도 못 받았는가. 비즈노까지 가보지도 못한 건이다."""
        return self.fetch.candidates is None


def resolve(
    name: str, address: str, provider: CandidateProvider, bizno: BiznoLookup
) -> Resolution:
    """가게 한 건을 조사한다.

    `bizno`는 `BiznoLookup`이면 된다 — 직접 호출이든 백엔드 경유든 캐시로 감싼 것이든
    구분하지 않는다.
    """
    fetched = fetch_candidates(provider, name, address)
    if fetched.candidates is None:
        return Resolution(None, [], 0, 0, 0, fetched)

    # 후보를 끝까지 전부 처리한다 — 어느 게 맞는지 모르므로 먼저 찾았다고 멈추지 않는다.
    pool: list[BiznoRecord] = []
    rejected = truncated = 0
    for candidate in fetched.candidates:
        # ① 다른 행정구역이면 조회할 이유가 없다. 주소는 기각에만 쓰므로,
        #    주소가 틀려도 "좋은 후보를 잃는" 쪽이지 "틀린 값을 채택하는" 쪽이 아니다.
        if address_conflicts(address, candidate.address):
            rejected += 1
            continue
        # ② 번호는 유일하게 식별된다. 이름 검색은 동명이인이 섞인다.
        if is_well_formed(candidate.biz_no):
            record = bizno.lookup_by_bizno(candidate.biz_no)
            if record is not None:
                pool.append(record)
                continue
        found, complete = bizno.search_by_name(candidate.name)
        if not complete:
            truncated += 1
        pool.extend(found)

    return Resolution(
        suggestion=suggest(name, pool),
        pool=pool,
        candidate_count=len(fetched.candidates),
        rejected_by_address=rejected,
        truncated_lookups=truncated,
        fetch=fetched,
    )
