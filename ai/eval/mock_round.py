"""2차 조사 측정 — 간이 DB(`mock_db.py`)의 행을 운영과 같은 경로로 조사하고 정답과 맞춰 본다.

    run:        간이 DB 행 → `StoreCheck` → (백엔드 대신) 대상 선별 → `InvestigationTarget` →
                `WebInvestigator`. 모델이 적어 온 **관측 원본**(값·날짜·출처)도 시트에 남긴다.
    reclassify: 저장한 관측에 **지금의** `classify()` 를 다시 적용한다. 분류 규칙을 고친 뒤
                Vertex 를 다시 부르지 않고 전후를 비교할 수 있다.
    map:        저장한 관측은 그대로 두고 카카오맵 확인만 새로 붙여 다시 분류한다.
    grade:      정답(인허가 폐업·영업)과 맞춰 센다.

간이 DB 는 선한영향력가게처럼 `phone=null`, `internalStatus=UNKNOWN` 이다. 그래서 여기서 나오는
우선확인이 **운영에서 우선확인 목록을 무엇이 채울지**를 보여 준다.

결과는 `eval/fixtures/mock_round_<태그>.csv` 에 한 건씩 덧붙인다(커밋 금지 경로). 끊겨도 다시
돌리면 이미 한 가게는 건너뛴다.

사용법:
    uv run --with pyproj python -m eval.mock_db --n 30   # 간이 DB 먼저 (좌표 변환에 pyproj)
    uv run python -m eval.mock_round run --tag v3
    uv run python -m eval.mock_round reclassify --tag v3 --out v3-rules2
    uv run python -m eval.mock_round map --tag v4 --out v5
    uv run python -m eval.mock_round grade --tag v3
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from collections import Counter
from dataclasses import asdict
from pathlib import Path

from dotenv import load_dotenv

AI_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(AI_ROOT / ".env")

from eval import mock_db  # noqa: E402
from src.backend_client.models import StoreCheck  # noqa: E402
from src.investigation import (  # noqa: E402
    ChangeField,
    InvestigationTarget,
    StoreFinding,
)
from src.investigation.classify import classify  # noqa: E402
from src.investigation.kakao_map import KakaoPlaceChecker  # noqa: E402
from src.investigation.models import PlaceCheck  # noqa: E402
from src.investigation.web import WebInvestigator  # noqa: E402
from src.investigation.web_research import (  # noqa: E402
    Observation,
    ResearchResult,
    Source,
    VertexResearchProvider,
)

PAUSE_SECONDS = 3.0  # 1초 간격에서도 429 가 났다

FINDING_COLUMNS = ["AI판정", "수정안", "신호", "근거"]
# "카카오" 칸에는 확인 결과(상태·place_url)만 남긴다 — 카카오 응답의 장소명·전화·좌표는 저장하지 않는다.
COLUMNS = ["storeId", "그룹", "사업장명", "주소", *FINDING_COLUMNS, "관측", "카카오", "실패"]


def _force_utf8_output() -> None:
    """윈도우 기본 콘솔(cp949)에서 리포트가 죽지 않게 한다 (`biz_number.py`와 같은 이유)."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def _sheet(tag: str) -> Path:
    return mock_db.FIXTURES / f"mock_round_{tag}.csv"


class CapturingProvider:
    """마지막으로 받은 관측을 기억해 두는 provider. 재시도로 여러 번 불리면 마지막 것이 남는다."""

    def __init__(self, inner: VertexResearchProvider) -> None:
        self._inner = inner
        self.last: ResearchResult | None = None

    def research(self, target: InvestigationTarget) -> ResearchResult:
        self.last = None
        self.last = self._inner.research(target)
        return self.last


def _dump_observations(result: ResearchResult) -> str:
    return json.dumps([asdict(o) for o in result.observations], ensure_ascii=False)


def _load_observations(text: str) -> ResearchResult:
    observations = []
    for o in json.loads(text or "[]"):
        o["field"] = ChangeField(o["field"])
        o["sources"] = tuple(Source(**s) for s in o["sources"])
        observations.append(Observation(**o))
    return ResearchResult(observations)


MAP_DISAGREES = "다른 가게 정보일 수 있음"


def change_summary(found: StoreFinding) -> str:
    """잡힌 변화(Signal)를 "status:CLOSED(2곳,지도와 다름)" 처럼 적는다."""
    return "; ".join(
        f"{s.field.value}:{s.observed}({s.source_count}곳{',지도와 다름' if MAP_DISAGREES in s.evidence_text else ''})"
        for s in found.signals
        if s.field
    )


def _finding_columns(found: StoreFinding) -> dict[str, str]:
    # 변화 Signal 의 대표 근거. 관측 전체는 "관측" 칸, 카카오맵 확인은 "카카오" 칸에 따로 있다.
    return {
        "AI판정": found.classification.value,
        "수정안": "; ".join(f"{k}={v}" for k, v in found.proposed_changes.items()),
        "신호": change_summary(found),
        "근거": "\n".join(f"{s.evidence_text} {s.evidence_url or ''}" for s in found.signals),
    }


def _read(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def _write(path: Path, rows: list[dict[str, str]]) -> None:
    # 엑셀에서 바로 열리게 BOM 을 붙인다.
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def run(tag: str) -> int:
    sheet = _sheet(tag)
    done = {r["storeId"] for r in _read(sheet) if r["AI판정"] != "실패"} if sheet.exists() else set()
    rows = [r for r in mock_db.load() if str(r["storeId"]) not in done]
    targets = []
    for row in rows:
        if not mock_db.is_second_round_target(StoreCheck.model_validate(row)):
            print(f"2차 조사 대상이 아니다: {row['name']}", file=sys.stderr)
            continue
        targets.append((row, InvestigationTarget.model_validate(row)))
    print(f"이미 한 것 {len(done)}곳 · 남은 것 {len(targets)}곳", flush=True)

    provider = CapturingProvider(VertexResearchProvider())
    investigator = WebInvestigator(provider, place_checker=KakaoPlaceChecker())
    kept = [r for r in _read(sheet) if r["AI판정"] != "실패"] if sheet.exists() else []
    for i, (row, target) in enumerate(targets):
        out = {
            "storeId": str(target.store_id),
            "그룹": row["answer_group"],
            "사업장명": target.name,
            "주소": target.address or "",
        }
        started = time.time()
        try:
            found = investigator.investigate(target)
            out |= _finding_columns(found)
            out["관측"] = _dump_observations(provider.last)
            out["카카오"] = found.map_check.model_dump_json() if found.map_check else ""
        except Exception as e:  # noqa: BLE001 - 한 건 실패로 표본 전체를 버리지 않는다
            out["AI판정"], out["실패"] = "실패", f"{type(e).__name__}: {e}"
        kept.append(out)
        _write(sheet, kept)  # 매 건 저장 — 끊겨도 이어서 돌린다
        print(
            f"[{i + 1:2}/{len(targets)}] {out['그룹']} {target.name} → {out['AI판정']} "
            f"{out.get('수정안', '')} ({time.time() - started:.0f}s)",
            flush=True,
        )
        time.sleep(PAUSE_SECONDS)
    print(f"→ {sheet}")
    return 0


def _load_place(row: dict[str, str]) -> PlaceCheck | None:
    return PlaceCheck.model_validate_json(row["카카오"]) if row.get("카카오") else None


def reclassify(tag: str, out_tag: str, *, with_map: bool = False) -> int:
    """저장한 관측에 지금의 분류 규칙을 다시 적용한다. Vertex 는 부르지 않는다.

    `with_map` 이면 카카오맵 확인을 새로 부른다(가게당 2회). 아니면 시트에 남은 확인 결과를 쓴다.
    """
    db = {str(r["storeId"]): r for r in mock_db.load()}
    checker = KakaoPlaceChecker() if with_map else None
    rows = _read(_sheet(tag))
    for row in rows:
        if row["AI판정"] == "실패":
            continue
        target = InvestigationTarget.model_validate(db[row["storeId"]])
        place = checker.check(target) if checker else _load_place(row)
        row["카카오"] = place.model_dump_json() if place else ""
        row |= _finding_columns(classify(target, _load_observations(row["관측"]), place))
    _write(_sheet(out_tag), rows)
    print(f"{len(rows)}곳 다시 분류 → {_sheet(out_tag)}")
    return grade(out_tag)


def _closing(row: dict[str, str]) -> int | None:
    """폐업·휴업을 가리키는 상태 신호의 출처 수. 신호가 없으면 None."""
    for part in filter(None, row["신호"].split("; ")):
        field_value, rest = part.split("(", 1)
        if field_value in ("status:CLOSED", "status:SUSPENDED"):
            return int(rest.split("곳")[0])
    return None


def _digits(value: str) -> str:
    return "".join(c for c in value if c.isdigit())


def grade(tag: str) -> int:
    db = {str(r["storeId"]): r for r in mock_db.load()}
    rows = _read(_sheet(tag))
    print(f"[{tag}]")
    for group in ("폐업", "영업"):
        members = [r for r in rows if r["그룹"] == group]
        ok = [r for r in members if r["AI판정"] != "실패"]
        print(f"\n{group} {len(members)}곳 (실패 {len(members) - len(ok)}곳 — 아래에서 뺐다)")
        if not ok:
            continue
        grounded = sum(1 for r in ok if any(o["sources"] for o in json.loads(r["관측"] or "[]")))
        closing = [n for r in ok if (n := _closing(r)) is not None]
        print(
            f"  근거 있음 {grounded}곳 · 폐업·휴업 신호 {len(closing)}곳"
            f" (출처 2곳 이상 {sum(n >= 2 for n in closing)}곳)"
        )
        print(f"  판정 {dict(Counter(r['AI판정'] for r in ok))}")
        places = {r["storeId"]: _load_place(r) for r in ok}
        if any(places.values()):
            by_verdict = Counter(
                (r["AI판정"], places[r["storeId"]].status.value if places[r["storeId"]] else "-") for r in ok
            )
            print("  카카오맵 " + ", ".join(f"{v}/{m} {n}" for (v, m), n in sorted(by_verdict.items())))
            flagged = [r for r in ok if "지도와 다름" in r["신호"]]
            if flagged:
                print(f"  지도와 어긋나는 변화 신호가 있는 가게 {len(flagged)}곳: " + ", ".join(r["사업장명"] for r in flagged))

        proposals = Counter()
        for r in ok:
            answer = db[r["storeId"]]
            for part in filter(None, r["수정안"].split("; ")):
                key, value = part.split("=", 1)
                if key == "status":
                    # 폐업 가게에 OPEN 을 제안하면 가장 위험한 오답이다.
                    # 휴업(SUSPENDED)도 문을 닫았다는 신호라 폐업 쪽으로 센다.
                    closing = value in ("CLOSED", "SUSPENDED")
                    verdict = "맞음" if closing == (group == "폐업") else "틀림"
                elif key == "phone" and answer["answer_phone"]:
                    verdict = "맞음" if _digits(value) == answer["answer_phone"] else "인허가와 다름"
                else:
                    verdict = "정답 없음"
                proposals[f"{key}={value if key == 'status' else ''}:{verdict}"] += 1
        if proposals:
            print("  수정안 " + ", ".join(f"{k} {v}" for k, v in sorted(proposals.items())))
    print("\n폐업 그룹의 폐업 신호가 재현율이다. 영업 그룹의 폐업 신호는 오탐 후보(인허가 '영업'은 약한 정답).")
    return 0


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="간이 DB 로 2차 조사 측정")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("run", "grade"):
        sub.add_parser(name).add_argument("--tag", required=True)
    for name in ("reclassify", "map"):
        re_parser = sub.add_parser(name)
        re_parser.add_argument("--tag", required=True)
        re_parser.add_argument("--out", required=True)
    args = parser.parse_args()
    if args.command == "run":
        return run(args.tag)
    if args.command in ("reclassify", "map"):
        return reclassify(args.tag, args.out, with_map=args.command == "map")
    return grade(args.tag)


if __name__ == "__main__":
    raise SystemExit(main())
