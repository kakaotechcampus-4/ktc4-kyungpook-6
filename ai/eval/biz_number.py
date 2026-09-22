"""웹검색 폴백 측정 — 후보 생성 방식이 사업자등록번호에 얼마나 도달하는가.

    모델 → 이름 후보 N개 + 근거·주소·번호
         → 주소가 다른 지역이면 기각 / 번호가 있으면 역조회 / 아니면 이름 검색
         → 비즈노 결과를 모아 등급 판정

지표는 **도달률**(후보 안에 정답 번호가 있는가)과 **오염률**(확실하다고 표시한 것 중 틀린
비율)이다. 오염률의 분모는 전체가 아니라 **`is_unambiguous` 표시가 붙은 건수**다 — 전체로
나누면 아무것도 표시하지 않을수록 지표가 좋아진다.
채택 정책은 의도적으로 단순하다 — 비즈노에 주소가 없어 여러 건 중 하나를 고를 근거가 없다.

**파싱 실패율도 함께 잰다.** 그라운딩 검색을 켜면 응답 형식을 강제할 수 없는데(`vertex.py`),
그 대가가 얼마인지는 관측하지 않으면 모른다. 재시도가 가려주기 전 수치를 센다.

`--model oracle`은 정답 이름을 후보로 넣어 **상한선**을 잰다. LLM 과금이 없다.
응답은 `eval/.cache/`에 저장하므로 재실행은 공짜다 — 비즈노 무료 티어가 1일 200건이라 중요하다.

사용법:
    uv run python -m eval.biz_number                  # mock, 과금 없음
    uv run python -m eval.biz_number --model oracle   # 상한선
    uv run python -m eval.biz_number --model gemini-2.5-flash-vertex
    uv run python -m eval.biz_number --limit 3        # 스모크 테스트

결과는 `docs/웹검색_폴백.md`.
"""

import argparse
import csv
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

from dotenv import load_dotenv

from src.biz_number.bizno import (
    DEFAULT_MAX_PAGES,
    BiznoClient,
    BiznoError,
    BiznoRecord,
    digits_only,
    is_well_formed,
)
from src.biz_number.matching import address_conflicts, suggest
from src.biz_number.web_search import (
    CANDIDATES_PROMPT_VERSION,
    MockCandidateProvider,
    NameCandidate,
    CandidateParseError,
)

AI_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(AI_ROOT / ".env")

FIXTURES_PATH = AI_ROOT / "eval" / "fixtures" / "testcases_name_mismatch_answers.csv"
CACHE_DIR = AI_ROOT / "eval" / ".cache"
LLM_CACHE_PATH = CACHE_DIR / "websearch_candidates.json"
BIZNO_CACHE_PATH = CACHE_DIR / "bizno_by_name.json"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def _save(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def load_bizno_cache() -> dict:
    """이름별 비즈노 결과 캐시. 같은 이름을 두 번 조회하지 않기 위한 것."""
    return _load(BIZNO_CACHE_PATH)


# 옛 캐시는 `pagecnt=10`이던 시절에 만들어졌다. PAGE_SIZE가 바뀌어도 이 판정은
# 그때 값으로 해야 한다 — 지금 값(50)으로 재면 10건짜리 옛 항목이 "완결"로 둔갑한다.
_LEGACY_PAGE_SIZE = 10


def _cached_records(entry) -> tuple[list[dict], bool]:
    """캐시 항목을 (레코드, 완결여부)로 읽는다.

    옛 포맷은 그냥 리스트였고 1페이지만 받아둔 것이다. 정확히 한 페이지 분량이면
    잘렸을 수 있으므로 미완결로 보고 다시 받는다.
    """
    if isinstance(entry, dict):
        return entry.get("records") or [], bool(entry.get("complete"))
    records = entry or []
    return records, len(records) < _LEGACY_PAGE_SIZE


class CachedBizno:
    """`src.fallback.BiznoLookup` 구현 + 디스크 캐시.

    측정을 여러 번 돌려도 호출량(1일 200건)을 다시 쓰지 않게 한다.
    조회 실패는 캐시하지 않는다 — 다음 실행에서 다시 시도해야 하기 때문이다.
    """

    def __init__(self, client: BiznoClient, cache: dict):
        self._client = client
        self._cache = cache

    def lookup_by_bizno(self, bizno: str) -> BiznoRecord | None:
        key = f"bizno:{digits_only(bizno)}"
        if key not in self._cache:
            try:
                record = self._client.lookup_by_bizno(bizno)
            except BiznoError as e:
                print(f"    [비즈노 실패] {bizno}: {e}", file=sys.stderr)
                return None
            # 번호 조회는 항상 1건이라 잘릴 일이 없다.
            self._cache[key] = {"records": [asdict(record)] if record else [], "complete": True}
        records, _ = _cached_records(self._cache[key])
        return BiznoRecord(**records[0]) if records else None

    def search_by_name(self, name: str) -> tuple[list[BiznoRecord], bool]:
        records, complete = _cached_records(self._cache.get(name))
        if name in self._cache and complete:
            return [BiznoRecord(**item) for item in records], True

        try:
            found, complete = self._client.search_by_name(name)
        except BiznoError as e:
            print(f"    [비즈노 실패] {name}: {e}", file=sys.stderr)
            return [BiznoRecord(**item) for item in records], False

        self._cache[name] = {"records": [asdict(r) for r in found], "complete": complete}
        return found, complete


LLM_ATTEMPTS = 2


def _vendor_api_errors() -> tuple[type[BaseException], ...]:
    """벤더 SDK의 API 예외. 로드돼 있을 때만 잡을 수 있으므로 sys.modules를 본다."""
    genai_errors = sys.modules.get("google.genai.errors")
    return (genai_errors.APIError,) if genai_errors is not None else ()


def is_contaminated(row: dict) -> bool:
    """정답지(`사업자등록번호`)를 믿을 수 없는 행인가 — E유형은 결제대행사 번호를 공유한다."""
    return (row.get("유형") or "").strip().startswith("E")


@dataclass(frozen=True)
class FetchResult:
    """후보 조회 한 건의 결과. **시도 단위 실패를 함께 들고 온다.**

    성공/실패만 돌려주면 재시도가 가려준 실패가 집계에서 사라진다 — 1차가 깨지고 2차가
    성공한 건이 "실패 0건"으로 보인다. 그러면 리포트가 말할 수 있는 건 "2회 안에는 됐다"
    뿐이고, 형식 강제를 포기한 대가가 실제로 얼마인지는 여전히 모른다.
    """

    candidates: list | None  # None이면 모든 시도가 실패했다
    failure: str  # 마지막 실패 사유. 성공이면 빈 문자열
    called: bool  # 실제로 모델을 불렀는가. 캐시 히트면 False — 실패율의 분모에서 빠진다
    parse_failures: int = 0  # 이 건에서 파싱이 깨진 시도 수
    api_failures: int = 0  # 이 건에서 벤더 API가 실패한 시도 수


def fetch_candidates(provider, name: str, address: str, cache: dict) -> FetchResult:
    """후보를 캐시에서 읽거나 호출한다. 실패해도 **무엇이 몇 번 깨졌는지** 함께 돌려준다.

    **파싱 실패는 캐시하지 않는다.** 예전에는 실패를 `[]`로 저장해서 세 가지가 겹쳤다.
      - 일시적 실패가 영구 캐시돼 다음 실행에서 재시도되지 않았다
      - 정상 0건(근거가 없어 후보를 못 낸 경우)과 구분되지 않았다
      - 리포트에 잡히지 않아 실패율을 알 수 없었다

    검색 도구를 쓰면 JSON 스키마를 강제할 수 없어 형식 이탈이 구조적으로 가능하다.
    그래서 한 번 더 시도하되, **재시도가 살려낸 건수도 세어서 올려보낸다** — 재시도가
    실제로 값을 하는지, 2단계 분리(검색 ON → 검색 OFF + 스키마 강제)까지 가야 하는지는
    그 수치로 판단할 일이다.
    """
    if name in cache:
        return FetchResult(candidates=cache[name], failure="", called=False)

    # 벤더 API 에러(레이트리밋·인증 만료·쿼터)는 파싱 실패와 원인이 다르다. 예전에는
    # 잡지 않아서 한 건이 터지면 실행 전체가 죽었고, 캐시가 마지막에 한 번만 저장되던
    # 탓에 그때까지 돈 주고 받은 응답이 전부 날아갔다. 케이스 단위로 격리하되
    # 사유는 구분해서 돌려준다 — 형식 문제와 인프라 문제를 같은 지표로 세면 안 된다.
    api_errors = _vendor_api_errors()
    last_error = ""
    parse_failures = api_failures = 0
    for _ in range(LLM_ATTEMPTS):
        try:
            candidates = [asdict(c) for c in provider.search_name_candidates(name, address)]
        except CandidateParseError as e:
            parse_failures += 1
            last_error = f"파싱:{e}"
            continue
        except api_errors as e:  # noqa: B030 - 런타임에 결정되는 예외 튜플
            api_failures += 1
            last_error = f"API:{type(e).__name__}: {e}"
            continue
        cache[name] = candidates
        return FetchResult(candidates, "", True, parse_failures, api_failures)
    return FetchResult(None, last_error, True, parse_failures, api_failures)


class _OracleProvider:
    """정답 등록상호명을 후보로 내놓는 가짜 provider — 배선 검증용.

    "모델이 완벽하다면 이 파이프라인이 몇 %에 도달하는가"를 LLM 과금 없이 확인한다.
    비즈노는 실제로 호출하므로 페이지 상한(`max_pages`)을 바꾸면 이 값도 움직인다.
    """

    def __init__(self, rows: list[dict]):
        self._by_name = {r["상호명"]: (r["국세청_등록상호명"] or "").strip() for r in rows}

    def search_name_candidates(self, store_name: str, _address: str) -> list[NameCandidate]:
        official = self._by_name.get(store_name)
        if not official:
            return []
        return [
            NameCandidate(name=official, evidence="fixture 정답지", source_url="fixture://oracle")
        ]


def _make_vertex_provider():
    """개인 GCP ADC가 필요하므로 이 모델을 실제로 고른 경우에만 만든다."""
    from src.biz_number.vertex import VertexWebSearchProvider

    return VertexWebSearchProvider(model="gemini-2.5-flash")


# 실제로 돌릴 수 있는 모델. 그라운딩이 Vertex 직결에서만 되므로 현재는 이것 하나다
# (카테캠 프록시는 provider-executed 도구를 거부한다 — `docs/웹검색_폴백.md` 참고).
MODEL_FACTORIES = {"gemini-2.5-flash-vertex": _make_vertex_provider}


def build_provider(model: str, rows: list[dict]):
    if model == "mock":
        return MockCandidateProvider()
    if model == "oracle":
        return _OracleProvider(rows)
    if model not in MODEL_FACTORIES:
        raise SystemExit(
            f"알 수 없는 모델: {model!r}. 선택 가능: {['mock', 'oracle', *sorted(MODEL_FACTORIES)]}"
        )
    return MODEL_FACTORIES[model]()


def _force_utf8_output() -> None:
    """리포트를 UTF-8로 내보낸다.

    윈도우 기본 콘솔이 cp949라 리포트에 섞인 문자 일부(`—` 등)에서
    UnicodeEncodeError로 죽는다. 수치와 캐시 저장은 그 전에 끝나므로 데이터가 날아가진
    않지만, 측정 결과를 다 찍기 전에 트레이스백이 나는 건 곤란하다.
    """
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="웹검색 후보 생성 방식 측정")
    parser.add_argument(
        "--model", default="mock", help="mock | oracle | 벤치마크 모델명. 기본 mock (과금 없음)"
    )
    parser.add_argument("--limit", type=int)
    parser.add_argument("--refresh", action="store_true", help="LLM 캐시를 무시하고 다시 호출한다")
    args = parser.parse_args()

    with FIXTURES_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if args.limit:
        rows = rows[: args.limit]

    provider = build_provider(args.model, rows)
    all_llm = _load(LLM_CACHE_PATH)
    cache_key = f"{args.model}@{CANDIDATES_PROMPT_VERSION}"
    llm_cache = {} if args.refresh else all_llm.get(cache_key, {})
    all_llm[cache_key] = llm_cache

    bizno_cache = load_bizno_cache()
    # oracle도 비즈노는 실제로 호출한다 — 상한선 측정이 목적이라
    # 캐시가 1페이지만 갖고 있으면 잘린 채로 재는 셈이 된다. LLM 호출만 없다.
    bizno = CachedBizno(BiznoClient(), bizno_cache)

    reached = contaminated = counted = no_candidate = 0
    excluded = total_rejected = 0
    # 오염률의 분모. 전체(`counted`)로 나누면 "표시를 적게 할수록 좋아지는" 지표가 된다.
    flagged = 0
    # 상한(`max_pages`)에 걸려 잘린 조회 수. 풀이 잘리면 등급·동점 판정이 달라져
    # 오염률이 흔들린다 — 수치를 읽을 때 필요한 측정 조건이다.
    truncated_lookups = 0
    # 실패율의 분모는 `counted`가 아니라 `called`다 — 캐시 히트는 호출이 없어 깨질 일도 없다.
    called = parse_failed_cases = parse_recovered = parse_dead = 0
    api_failed_cases = api_dead = 0

    print(f"모델: {args.model}\n")
    print(f"{'상호명':<24} {'후보':<5} {'주소기각':<7} {'비즈노':<7} {'최고등급':<20} {'도달':<5}")
    print("-" * 80)

    for row in rows:
        name, address = row["상호명"], row["정제도로명주소"] or row["정제지번주소"]

        result = fetch_candidates(provider, name, address, llm_cache)
        # 호출 결과를 바로 저장한다. 실행이 중간에 죽어도 이미 산 응답은 지킨다.
        _save(LLM_CACHE_PATH, all_llm)

        if result.called:
            called += 1
            # 최종 성공했어도 중간에 깨졌으면 센다 — 이게 재시도가 가려주던 수치다.
            if result.parse_failures:
                parse_failed_cases += 1
                if result.candidates is not None:
                    parse_recovered += 1
            if result.api_failures:
                api_failed_cases += 1

        if result.candidates is None:
            counted += 0 if is_contaminated(row) else 1
            if result.failure.startswith("API:"):
                api_dead += 1
                label = "API 실패"
            else:
                parse_dead += 1
                label = "파싱 실패"
            print(f"{name[:22]:<24} [{label} {LLM_ATTEMPTS}회] {result.failure[:60]}")
            continue
        candidates = [NameCandidate(**c) for c in result.candidates]

        # 후보를 끝까지 전부 처리한다 — 어느 게 맞는지 모르므로 먼저 찾았다고 멈추지 않는다.
        pooled: list[BiznoRecord] = []
        rejected = 0
        for candidate in candidates:
            # ① 다른 행정구역이면 조회할 이유가 없다. 주소는 기각에만 쓰므로,
            #    주소가 틀려도 "좋은 후보를 잃는" 쪽이지 "틀린 값을 채택하는" 쪽이 아니다.
            if address_conflicts(address, candidate.address):
                rejected += 1
                continue
            # ② 번호는 유일하게 식별된다. 이름 검색은 동명이인이 섞인다.
            if is_well_formed(candidate.biz_no):
                record = bizno.lookup_by_bizno(candidate.biz_no)
                if record is not None:
                    pooled.append(record)
                    continue
            found, complete = bizno.search_by_name(candidate.name)
            if not complete:
                truncated_lookups += 1
            pooled.extend(found)

        s = suggest(name, pooled)
        is_e_type = is_contaminated(row)
        expected = digits_only(row["사업자등록번호"])
        hit = any(digits_only(r.bizno) == expected for r in pooled)

        if is_e_type:
            excluded += 1
            mark = "제외"
        else:
            counted += 1
            if hit:
                reached += 1
            if s.is_unambiguous:
                flagged += 1
                if s.best and digits_only(s.best.record.bizno) != expected:
                    contaminated += 1
            if not candidates:
                no_candidate += 1
            total_rejected += rejected
            mark = "O" if hit else "X"

        _save(BIZNO_CACHE_PATH, bizno_cache)
        print(
            f"{name[:22]:<24} {len(candidates):<5} {rejected:<7} {len(pooled):<7} "
            f"{(s.best.label if s.best else '후보 없음'):<20} {mark:<5}"
        )

    _save(LLM_CACHE_PATH, all_llm)
    _save(BIZNO_CACHE_PATH, bizno_cache)

    print("-" * 80)
    if counted:
        print(f"집계 대상 {counted}건 (E유형 {excluded}건 제외)")
        print(f"  번호 도달률   {reached}/{counted} = {reached / counted:.1%}")
        if flagged:
            print(
                f"  오염률        {contaminated}/{flagged} = {contaminated / flagged:.1%}"
                f"  (확실 표시가 붙은 건 중 오답)"
            )
        else:
            print("  오염률        측정 불가 — 확실 표시가 붙은 건이 0건이다")
        print(f"  후보 0건      {no_candidate}/{counted}")
        print(f"  주소 불일치로 기각한 후보  {total_rejected}개")
        print(
            f"  측정 조건     비즈노 max_pages={DEFAULT_MAX_PAGES},"
            f" 상한에 걸려 잘린 조회 {truncated_lookups}건"
        )

    # 형식 강제를 못 쓰는 대가. 도달률과 분모가 다르므로(캐시 히트 제외) 따로 출력한다.
    if called:
        print()
        print(f"실제 호출 {called}건 기준")
        print(
            f"  응답 파싱 실패  {parse_failed_cases}/{called} = {parse_failed_cases / called:.1%}"
            f"  (재시도로 복구 {parse_recovered}건, {LLM_ATTEMPTS}회 모두 실패 {parse_dead}건)"
        )
        print(
            f"  벤더 API 실패   {api_failed_cases}/{called}"
            f"  ({LLM_ATTEMPTS}회 모두 실패 {api_dead}건 — 레이트리밋·인증 등, 형식과 무관)"
        )
    else:
        print()
        print("파싱 실패율   측정 불가 — 호출이 없었다(전부 캐시 히트). --refresh로 재호출할 것")
    print(f"\n캐시: {LLM_CACHE_PATH.name}, {BIZNO_CACHE_PATH.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
