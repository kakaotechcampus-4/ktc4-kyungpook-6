"""값 불일치 벤치마크 — 2차 조사의 주 목표(주소·전화·상호의 사소한 불일치)를 재는 장치.

간이 DB(`mock_db.py`)는 정답이 "인허가 폐업·영업"뿐이라 폐업만 잴 수 있다. 여기서는 인허가에서 **영업 중인**
가게를 뽑아 "담당자가 확인을 마친" DB 처럼 만든 뒤(상태 OPEN, 전화 채움, 도로명 주소), 일부 가게의 한 항목을
**일부러 조금 틀리게** 넣는다. 조사가 틀린 값을 잡는지(재현율), 맞는 값을 틀렸다고 하지 않는지(오탐)를 센다.

    확인된 값  인허가의 사업장명·도로명주소·전화번호. 인허가 전화는 옛 번호일 수 있다 — 그래서 대조군에서
               나온 전화 제안은 "오탐 후보"로만 센다
    틀리게 넣기 (가게마다 한 항목만, 시드 고정)
      phone    마지막 네 자리 중 한 자리를 바꾼다            053-567-3050 → 053-567-3080
      address  도로명 건물번호를 2 늘린다                   국채보상로67길 38 → 국채보상로67길 40
      name     핵심 이름의 한 글자 모음을 바꾼다(오타)        빠레뜨치킨 → 빠래뜨치킨
      control  손대지 않는다

결과는 `eval/fixtures/mismatch_bench*.csv` 에 쓴다(실제 가게 정보 — 커밋 금지 경로).

사용법:
    uv run --with pyproj python -m eval.mismatch_bench build --n 60
    uv run python -m eval.mismatch_bench run --tag m1                 # 웹검색만(원래 운영)
    uv run python -m eval.mismatch_bench run --tag f1 --mode fixed    # 지도 먼저, 안 되면 웹(고정 순서)
    uv run python -m eval.mismatch_bench run --tag a1 --mode agent    # 에이전트(AGENT_MODEL 이 있으면 그 모델이 판단)
    uv run python -m eval.mismatch_bench grade --tag m1

결과(2026-10-06, 같은 58곳·오류 34): 웹만 10 · 고정 순서 22(LLM 가게당 0.5회, 7초) · 에이전트 Gemini 판단 20(4.2회,
27초) · 에이전트 gpt-6-luna 판단 22(4.1회, 25초). 대조군 깨끗 셋 다 19/24.
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import time
from collections import Counter

from eval import mock_db
from eval.mock_round import _force_utf8_output
from src.investigation import ChangeField, InvestigationTarget
from src.investigation.classify import comparison_key, coverage, domestic_phone, road_address_key
from src.investigation.kakao_map import KakaoPlaceChecker
from src.investigation.web import WebInvestigator
from src.investigation.web_research import ResearchResult, VertexResearchProvider

BENCH_PATH = mock_db.FIXTURES / "mismatch_bench.csv"
SEED = 20261006
CASES = ("control", "phone", "address", "name")
PAUSE_SECONDS = 3.0

RESULT_COLUMNS = ["storeId", "사업장명", "case", "field", "truth", "injected", "AI판정", "수정안", "신호항목", "덩어리",
                  "LLM호출", "관측", "검색어", "과정", "실패", "초"]


# ---------------------------------------------------------------- 틀리게 넣기

def perturb_phone(phone: str, rng: random.Random) -> str:
    """마지막 네 자리 중 한 자리를 다른 숫자로. 국내 번호 모양은 그대로다."""
    head, last = phone[:-4], list(phone[-4:])
    i = rng.randrange(4)
    last[i] = str((int(last[i]) + rng.randrange(1, 10)) % 10)
    return head + "".join(last)


_ROAD_NUMBER = re.compile(r"((?:로|길)\s*)(\d+)(?=(?:-\d+)?(?![가-힣\d]))")


def perturb_address(address: str) -> str | None:
    """도로명 뒤 건물번호를 2 늘린다. 도로명 주소가 아니면 None."""
    match = _ROAD_NUMBER.search(address)
    if not match:
        return None
    return address[: match.start(2)] + str(int(match[2]) + 2) + address[match.end(2):]


# 오타로 흔한 모음 바꿈: ㅐ↔ㅔ, ㅏ↔ㅓ, ㅗ↔ㅜ, ㅑ↔ㅕ, ㅛ↔ㅠ (중성 번호)
_VOWEL_SWAP = {1: 5, 5: 1, 0: 4, 4: 0, 8: 13, 13: 8, 2: 6, 6: 2, 12: 17, 17: 12}


def perturb_name(name: str, rng: random.Random) -> str | None:
    """핵심 이름(괄호 앞)의 한 글자 모음을 바꾼다. 첫 글자는 남긴다 — 검색이 아예 빗나가지 않게."""
    core_end = min((i for i, c in enumerate(name) if c in "([（"), default=len(name))
    candidates = [
        i for i in range(1, core_end)
        if "가" <= name[i] <= "힣" and ((ord(name[i]) - 0xAC00) // 28) % 21 in _VOWEL_SWAP
    ]
    if not candidates:
        return None
    i = rng.choice(candidates)
    code = ord(name[i]) - 0xAC00
    cho, jung, jong = code // 588, (code // 28) % 21, code % 28
    swapped = chr(0xAC00 + (cho * 21 + _VOWEL_SWAP[jung]) * 28 + jong)
    return name[:i] + swapped + name[i + 1:]


# ---------------------------------------------------------------- 만들기

def build(n: int) -> int:
    to_wgs84 = mock_db._to_wgs84()
    opened = [r for group, r in mock_db.pick(mock_db.load_license_rows(), n) if group == "영업"]
    rows = []
    for i, r in enumerate(opened):
        rng = random.Random(f"{SEED}-{mock_db._get(r, '관리번호')}")
        base = mock_db.to_store_check(i + 1, "영업", r, to_wgs84)
        name, road = mock_db._get(r, "사업장명"), mock_db._get(r, "도로명주소")
        phone = domestic_phone(mock_db._get(r, "전화번호") or "")
        row = base | {
            "name": name,
            "nameNormalized": mock_db.normalize_name(name),
            "addressRoad": road,
            "addressNormalized": mock_db.normalize_address(road),
            "phone": phone,
            "internalStatus": "OPEN",
        }
        case = CASES[i % len(CASES)]
        injected = {"phone": perturb_phone(phone, rng) if phone else None,
                    "address": perturb_address(road),
                    "name": perturb_name(name, rng)}.get(case) if case != "control" else None
        if case != "control" and injected is None:
            case = "control"  # 이 가게는 그 항목을 틀리게 넣을 수 없다(전화 없음 등)
        field = {"phone": ChangeField.PHONE, "address": ChangeField.ADDRESS, "name": ChangeField.NAME}.get(case)
        truth = {"phone": phone, "address": road, "name": name}.get(case)
        if field is ChangeField.PHONE:
            row["phone"] = injected
        elif field is ChangeField.ADDRESS:
            row |= {"addressRoad": injected, "addressNormalized": mock_db.normalize_address(injected)}
        elif field is ChangeField.NAME:
            row |= {"name": injected, "nameNormalized": mock_db.normalize_name(injected)}
        row |= {
            "bench_case": case,
            "bench_field": field.value if field else "",
            "bench_truth": truth or "",
            "bench_injected": injected or "",
            "truth_name": name,
            "truth_addressRoad": road,
            "truth_phone": phone or "",
        }
        rows.append(row)
    with BENCH_PATH.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"{len(rows)}곳 → {BENCH_PATH}")
    print("  " + ", ".join(f"{k} {v}" for k, v in Counter(r["bench_case"] for r in rows).items()))
    return 0


def load_bench() -> list[dict]:
    with BENCH_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    for row in rows:
        for key, value in row.items():
            if value == "":
                row[key] = None
    return rows


# ---------------------------------------------------------------- 돌리기

def _sheet(tag: str):
    return mock_db.FIXTURES / f"mismatch_bench_{tag}.csv"


class _Capturing:
    def __init__(self, inner) -> None:
        self._inner, self.last, self.queries, self.calls = inner, None, [], 0
        self._client = getattr(inner, "_client", None)

    def research(self, target: InvestigationTarget) -> ResearchResult:
        self.calls += 1
        self.last = self._inner.research(target)
        self.queries += self.last.queries
        return self.last

    def research_with_prompt(self, prompt: str) -> ResearchResult:
        self.calls += 1
        self.last = self._inner.research_with_prompt(prompt)
        self.queries += self.last.queries
        return self.last


class _CapturingMaps:
    def __init__(self, inner) -> None:
        self._inner, self.last = inner, []

    def observe(self, target):
        self.last = self._inner.observe(target)
        return self.last


def _investigator(mode: str, provider: _Capturing, maps: _CapturingMaps):
    """web: 지금 운영(웹검색만) · fixed: 지도 먼저, 안 되면 웹(규칙) · agent: 에이전트가 도구 선택."""
    if mode == "web":
        return WebInvestigator(provider, place_checker=KakaoPlaceChecker())
    if mode == "fixed":
        return WebInvestigator(provider, map_lookup=maps)  # 카카오는 값 대조 한 경로만(운영과 같게)
    from src.investigation.agent import AgentInvestigator, OpenAIDecider
    # AGENT_MODEL 이 있으면 그 모델(OpenAI 호환, 카테캠 프록시 등)이 판단하고, 없으면 Vertex Gemini 가 판단한다.
    return AgentInvestigator(provider, map_lookup=maps, decider=OpenAIDecider.from_env())


def run(tag: str, mode: str = "web") -> int:
    sheet = _sheet(tag)
    kept = []
    if sheet.exists():
        with sheet.open(encoding="utf-8-sig", newline="") as f:
            kept = [r for r in csv.DictReader(f) if not r["실패"]]
    done = {r["storeId"] for r in kept}
    from src.investigation.map_lookup import KakaoLocalLookup, MapLookup, NaverLocalLookup

    provider = _Capturing(VertexResearchProvider())
    maps = _CapturingMaps(MapLookup([NaverLocalLookup(), KakaoLocalLookup()]))
    investigator = _investigator(mode, provider, maps)
    rows = [r for r in load_bench() if r["storeId"] not in done]
    print(f"이미 한 것 {len(done)}곳 · 남은 것 {len(rows)}곳", flush=True)
    for i, row in enumerate(rows):
        target = InvestigationTarget.model_validate(row)
        out = {"storeId": row["storeId"], "사업장명": row["truth_name"], "case": row["bench_case"],
               "field": row["bench_field"] or "", "truth": row["bench_truth"] or "", "injected": row["bench_injected"] or ""}
        provider.last, provider.queries, provider.calls, maps.last = None, [], 0, []
        started = time.time()
        try:
            found = investigator.investigate(target)
            trace = getattr(investigator, "last_trace", None)
            web = provider.last.observations if provider.last else []
            # 카카오 값(storable=False)은 시트에 남기지 않는다 — 실시간 비교 후 폐기만 허용(약관).
            kept_obs = [o for o in maps.last + web if o.sources and o.storable]
            out |= {
                "AI판정": found.classification.value,
                "수정안": json.dumps(found.proposed_changes, ensure_ascii=False),
                "신호항목": json.dumps([s.field.value for s in found.signals if s.field]),
                "덩어리": coverage(target, investigator.last_observations if mode == "agent" else maps.last + web),
                "LLM호출": str(trace.llm_calls if trace else provider.calls),
                "관측": json.dumps(
                    [{"field": o.field.value, "value": o.value, "observed_at": o.observed_at,
                      "domains": sorted(o.domains)} for o in kept_obs],
                    ensure_ascii=False,
                ),
                "검색어": json.dumps(provider.queries, ensure_ascii=False),
                "과정": " | ".join(trace.steps) if trace else "",
            }
        except Exception as e:  # noqa: BLE001 - 한 건 실패로 표본을 버리지 않는다
            out["실패"] = f"{type(e).__name__}: {e}"[:300]
        out["초"] = f"{time.time() - started:.0f}"
        kept.append(out)
        with sheet.open("w", encoding="utf-8-sig", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=RESULT_COLUMNS)
            writer.writeheader()
            writer.writerows(kept)
        print(f"[{i + 1:2}/{len(rows)}] {out['case']:7} {row['truth_name']} → {out.get('수정안', '실패')} ({out['초']}s)", flush=True)
        time.sleep(PAUSE_SECONDS)
    return grade(tag)


# ---------------------------------------------------------------- 채점

def _key(field: str, value: str) -> str:
    return comparison_key(ChangeField(field), value)


def _matches_truth(field: str, proposed: str, truth: str) -> bool:
    """제안한 값이 확인된 값과 같은가. 상호는 한쪽이 다른 쪽을 품으면 같다(지점 표기)."""
    if field == ChangeField.ADDRESS.value:
        return (road_address_key(proposed) or _key(field, proposed)) == (road_address_key(truth) or _key(field, truth))
    a, b = _key(field, proposed), _key(field, truth)
    if field == ChangeField.NAME.value:
        return bool(a and b) and (a in b or b in a)
    return a == b


def grade(tag: str) -> int:
    with _sheet(tag).open(encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.DictReader(f) if not r["실패"]]
    bench = {r["storeId"]: r for r in load_bench()}
    failed = len(bench) - len(rows)
    print(f"\n[{tag}] {len(rows)}곳 채점 (실패·미실행 {failed}곳)")

    print("\n틀리게 넣은 항목을 잡았나 (재현율)")
    for case in ("phone", "address", "name"):
        members = [r for r in rows if r["case"] == case]
        # 수정안이 없어도 그 항목의 Signal 이 있으면 잡은 것이다(카카오만 다른 값 → 근거·링크만 남음).
        caught = [r for r in members
                  if r["field"] in json.loads(r["수정안"] or "{}") or r["field"] in json.loads(r.get("신호항목") or "[]")]
        fixed = [r for r in caught if r["field"] in json.loads(r["수정안"] or "{}")
                 and _matches_truth(r["field"], json.loads(r["수정안"])[r["field"]], r["truth"])]
        print(f"  {case:7} {len(members)}곳 · 잡음 {len(caught)} · 그중 확인된 값으로 고침 {len(fixed)}")
        for r in members:
            proposal = json.loads(r["수정안"] or "{}").get(r["field"])
            mark = "✓" if proposal and _matches_truth(r["field"], proposal, r["truth"]) else ("△" if proposal else "·")
            print(f"     {mark} {r['사업장명']}: 넣은 값 {r['injected']} → 제안 {proposal or '-'} (확인된 값 {r['truth']})")

    print("\n건드리지 않은 항목에 낸 제안 (오탐 후보)")
    false_alarms = Counter()
    def flagged(r: dict) -> dict:
        """수정안 + 수정안 없는 Signal(값 None). 카카오만 다른 값을 가리키면 값 없이 Signal 만 있다."""
        out = json.loads(r["수정안"] or "{}")
        for f in json.loads(r.get("신호항목") or "[]"):
            out.setdefault(f, None)
        return out

    for r in rows:
        b = bench[r["storeId"]]
        for field, value in flagged(r).items():
            if field == r["field"]:
                continue
            truth = {"phone": b["truth_phone"], "addressRoad": b["truth_addressRoad"], "name": b["truth_name"]}.get(field)
            if field == ChangeField.PHONE.value and not b["phone"]:
                kind = "빈 값 채움"  # DB 에 번호가 없던 가게 — 오탐이 아니라 채움이다
            elif value is None:
                kind = "신호만(지도와 다름)"
            elif truth and _matches_truth(field, value, truth):
                kind = "확인된 값과 같음"
            else:
                kind = "다름"
            false_alarms[(field, kind)] += 1
            print(f"     {r['case']:7} {r['사업장명']}: {field}={value} (확인된 값 {truth or '-'}, {kind})")
    print("  " + (", ".join(f"{f}({k}) {n}" for (f, k), n in sorted(false_alarms.items())) or "없음"))
    controls = [r for r in rows if r["case"] == "control"]
    clean = sum(
        1 for r in controls
        if not [f for f in flagged(r) if not (f == "phone" and not bench[r["storeId"]]["phone"])]
    )
    llm = [int(r["LLM호출"]) for r in rows if r.get("LLM호출")]
    print(f"\n대조군 {len(controls)}곳 중 제안 없음(빈 값 채움 제외) {clean}곳 · 평균 {sum(int(r['초']) for r in rows) / max(len(rows), 1):.0f}초"
          + (f" · LLM 호출 평균 {sum(llm) / len(llm):.1f}회(합 {sum(llm)})" if llm else ""))
    tiers = Counter(r.get("덩어리") for r in rows if r.get("덩어리"))
    if tiers:
        wrongly = [r["사업장명"] for r in rows if r["case"] != "control" and r.get("덩어리") == "confirmed"]
        print(f"덩어리: 수정 필요 {tiers['changed']} · 직접 확인 필요 {tiers['unresolved']} · 확인됨 {tiers['confirmed']}"
              f" ({tiers['confirmed'] / len(rows):.0%}) · 틀린 가게가 확인됨으로 빠짐 {len(wrongly)}")
    print("인허가 전화는 옛 번호일 수 있다 — 전화 오탐 후보는 근거를 열어 확인해야 한다.")
    return 0


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="값 불일치 벤치마크")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("build").add_argument("--n", type=int, default=60)
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--tag", required=True)
    run_parser.add_argument("--mode", choices=("web", "fixed", "agent"), default="web")
    sub.add_parser("grade").add_argument("--tag", required=True)
    args = parser.parse_args()
    if args.command == "build":
        return build(args.n)
    if args.command == "run":
        return run(args.tag, args.mode)
    return grade(args.tag)


if __name__ == "__main__":
    raise SystemExit(main())
