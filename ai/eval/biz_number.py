"""웹검색 폴백 측정 — 후보 생성 방식이 사업자등록번호에 얼마나 도달하는가.

**조사 로직은 `src.biz_number.resolve.resolve()`에 있다.** 여기는 그것을 fixture에
돌려 채점하는 쪽이다 — 운영도 같은 함수를 부르므로 여기서 잰 값이 운영 성능이 된다.

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

from src.biz_number.bizno import DEFAULT_MAX_PAGES, BiznoClient
from src.biz_number.lookup import BiznoError, BiznoRecord, digits_only
from src.biz_number.resolve import LLM_ATTEMPTS, resolve
from src.biz_number.web_search import (
    CANDIDATES_PROMPT_VERSION,
    MockCandidateProvider,
    NameCandidate,
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


def is_contaminated(row: dict) -> bool:
    """정답지(`사업자등록번호`)를 믿을 수 없는 행인가 — E유형은 결제대행사 번호를 공유한다."""
    return (row.get("유형") or "").strip().startswith("E")


class CachedProvider:
    """후보 생성 provider + 디스크 캐시 — **측정 전용**이다.

    운영은 매번 새로 물어야 하므로 `resolve()`가 아니라 여기 있다.

    **실패는 캐시하지 않는다** — 예외를 올려보내 재시도가 동작하게 둔다. 예전에 실패를
    `[]`로 저장했다가 재시도가 막히고, 정상 0건과 구분이 안 되고, 실패율이 안 잡혔다.
    """

    def __init__(self, inner, cache: dict):
        self._inner = inner
        self._cache = cache
        self.hits = 0  # 캐시 히트 수. 실패율의 분모(실제 호출 건수)를 세는 데 쓴다

    def search_name_candidates(self, store_name: str, address: str) -> list[NameCandidate]:
        if store_name in self._cache:
            self.hits += 1
            return [NameCandidate(**c) for c in self._cache[store_name]]
        found = self._inner.search_name_candidates(store_name, address)
        self._cache[store_name] = [asdict(c) for c in found]
        return found


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

    all_llm = _load(LLM_CACHE_PATH)
    cache_key = f"{args.model}@{CANDIDATES_PROMPT_VERSION}"
    llm_cache = {} if args.refresh else all_llm.get(cache_key, {})
    all_llm[cache_key] = llm_cache
    provider = CachedProvider(build_provider(args.model, rows), llm_cache)

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

        cache_hits_before = provider.hits
        r = resolve(name, address, provider, bizno)
        # 호출 결과를 바로 저장한다. 실행이 중간에 죽어도 이미 산 응답은 지킨다.
        _save(LLM_CACHE_PATH, all_llm)
        _save(BIZNO_CACHE_PATH, bizno_cache)

        # 캐시 히트는 호출이 없어 깨질 일도 없으므로 실패율의 분모에서 뺀다.
        if provider.hits == cache_hits_before:
            called += 1
            # 최종 성공했어도 중간에 깨졌으면 센다 — 이게 재시도가 가려주던 수치다.
            if r.fetch.parse_failures:
                parse_failed_cases += 1
                if not r.failed:
                    parse_recovered += 1
            if r.fetch.api_failures:
                api_failed_cases += 1

        if r.failed:
            counted += 0 if is_contaminated(row) else 1
            if r.fetch.failure.startswith("API:"):
                api_dead += 1
                label = "API 실패"
            else:
                parse_dead += 1
                label = "파싱 실패"
            print(f"{name[:22]:<24} [{label} {LLM_ATTEMPTS}회] {r.fetch.failure[:60]}")
            continue

        s_ = r.suggestion
        truncated_lookups += r.truncated_lookups
        expected = digits_only(row["사업자등록번호"])
        hit = any(digits_only(x.bizno) == expected for x in r.pool)

        if is_contaminated(row):
            excluded += 1
            mark = "제외"
        else:
            counted += 1
            if hit:
                reached += 1
            if s_.is_unambiguous:
                flagged += 1
                if s_.best and digits_only(s_.best.record.bizno) != expected:
                    contaminated += 1
            if not r.candidate_count:
                no_candidate += 1
            total_rejected += r.rejected_by_address
            mark = "O" if hit else "X"

        print(
            f"{name[:22]:<24} {r.candidate_count:<5} {r.rejected_by_address:<7} {len(r.pool):<7} "
            f"{(s_.best.label if s_.best else '후보 없음'):<20} {mark:<5}"
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
