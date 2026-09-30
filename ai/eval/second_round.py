"""2차 조사 측정 — 정답지를 만들고(sample), 채점한다(grade).

정답이 붙은 가게 목록이 없어서 **조사를 먼저 돌리고 사람이 결과에 정답을 붙인다.**

    sample: 경기도 선한영향력가게 목록에서 N곳을 뽑아 2차 조사를 돌리고 확인 시트를 만든다.
            상태·주소·상호 신호가 있는 가게는 전부, 없는 가게는 일부만 "확인대상"으로 표시한다.
    (사람): 확인대상 행을 지도로 확인해 정답 칸을 채운다.
    grade:  채운 시트로 정밀도와 (표본 기반) 놓친 비율을 센다.

**조사 로직은 `src.investigation`에 있다.** 운영과 같은 `WebInvestigator`를 부른다.

목록에는 전화번호 칸이 없어 **전화번호 변경은 잴 수 없다.** DB 전화가 비어 있으니 찾은 번호는
전부 "새 값" 신호가 되는데, 정답을 붙일 수 없으므로 확인대상을 고를 때와 채점할 때 전화 신호는
보지 않는다. 목록에 있다는 것 자체가 참여 중이라는 뜻이라 우리 상태는 모두 OPEN 으로 넣는다.

시트는 `eval/fixtures/`(커밋 금지 경로)에 저장한다 — 실제 가게 정보와 웹 근거가 들어 있다.

사용법:
    uv run python -m eval.second_round sample --n 60
    uv run python -m eval.second_round sample --n 3          # 스모크 테스트
    uv run python -m eval.second_round grade
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
import time
from collections import Counter
from pathlib import Path
from urllib.parse import quote

from dotenv import load_dotenv

AI_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(AI_ROOT / ".env")

from src.investigation import InvestigationTarget  # noqa: E402
from src.investigation.web import WebInvestigator  # noqa: E402
from src.investigation.web_research import VertexResearchProvider  # noqa: E402
from eval.mock_round import change_summary  # noqa: E402

SOURCE_PATH = AI_ROOT / "eval" / "fixtures" / "경기도선한영향력가게현황.csv"
SHEET_PATH = AI_ROOT / "eval" / "fixtures" / "second_round_labels.csv"
SEED = 20260924
NO_CHANGE_TO_CHECK = 10  # 변화없음 중 사람이 확인할 수 (놓친 비율 표본)
PAUSE_SECONDS = 1.0  # 연달아 부르면 429 가 난다

#: 사람이 채우는 칸. 값은 "변화없음" / 바뀐 값 / "모름". 비워 두면 채점에서 뺀다.
ANSWER_COLUMNS = ["정답_상태", "정답_주소", "정답_상호", "확인_근거", "비고"]


def _force_utf8_output() -> None:
    """윈도우 기본 콘솔(cp949)에서 리포트가 죽지 않게 한다 (`biz_number.py`와 같은 이유)."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def _load_stores() -> list[dict[str, str]]:
    # 경기데이터드림 원본은 cp949 다.
    with SOURCE_PATH.open(encoding="cp949", newline="") as f:
        rows = list(csv.DictReader(f))
    return [r for r in rows if r["정제도로명주소"] or r["정제지번주소"]]


def _map_links(name: str, address: str) -> tuple[str, str]:
    query = quote(f"{name} {address.split(' ')[1] if ' ' in address else ''}".strip())
    return f"https://map.naver.com/p/search/{query}", f"https://map.kakao.com/?q={query}"


def sample(n: int) -> int:
    stores = _load_stores()
    picked = random.Random(SEED).sample(stores, min(n, len(stores)))
    investigator = WebInvestigator(VertexResearchProvider())

    sheet = []
    for i, store in enumerate(picked):
        address = store["정제도로명주소"] or store["정제지번주소"]
        target = InvestigationTarget(
            store_id=i, name=store["상호명"], address=address, internal_status="OPEN"
        )
        started = time.time()
        try:
            found = investigator.investigate(target)
            verdict = found.classification.value
            gradable = any(k != "phone" for k in found.proposed_changes)
            changes = "; ".join(f"{k}={v}" for k, v in found.proposed_changes.items())
            signals = change_summary(found)
            evidences = "\n".join(
                f"{s.evidence_text} {s.evidence_url or ''}" for s in found.signals[:6]
            )
        except Exception as e:  # noqa: BLE001 - 한 건 실패로 표본 전체를 버리지 않는다
            verdict, changes, signals, evidences = "실패", "", "", f"{type(e).__name__}: {e}"
            gradable = False
        naver, kakao = _map_links(store["상호명"], address)
        sheet.append(
            {
                "번호": i,
                "상호명": store["상호명"],
                "주소": address,
                "업종": store["업종명"],
                "AI판정": verdict,
                "수정안": changes,
                "신호": signals,
                "근거": evidences,
                "네이버지도": naver,
                "카카오지도": kakao,
                "확인대상": "",
                "_신호있음": gradable,
                **{c: "" for c in ANSWER_COLUMNS},
            }
        )
        print(f"[{i:2}] {store['상호명']} → {verdict} {signals} ({time.time() - started:.0f}s)", flush=True)
        time.sleep(PAUSE_SECONDS)

    flagged = [r for r in sheet if r.pop("_신호있음")]
    unflagged = [r for r in sheet if r not in flagged and r["AI판정"] != "실패"]
    for r in flagged:
        r["확인대상"] = "Y"
    for r in random.Random(SEED).sample(unflagged, min(NO_CHANGE_TO_CHECK, len(unflagged))):
        r["확인대상"] = "표본"

    # 엑셀에서 바로 열리게 BOM 을 붙인다.
    with SHEET_PATH.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(sheet[0]))
        writer.writeheader()
        writer.writerows(sheet)

    print()
    print(f"판정 {dict(Counter(r['AI판정'] for r in sheet))}")
    print(f"신호 있음(확인대상 Y) {len(flagged)}곳 · 신호 없음 표본 {sum(r['확인대상'] == '표본' for r in sheet)}곳")
    print(f"→ {SHEET_PATH}")
    return 0


def _changed(answer: str) -> bool | None:
    """정답 칸 하나가 변화를 뜻하는가. 비었거나 "모름"이면 None."""
    answer = answer.strip()
    if not answer or answer == "모름":
        return None
    return answer != "변화없음"


def grade() -> int:
    with SHEET_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.DictReader(f) if r["확인대상"] in ("Y", "표본")]

    labeled = []
    for r in rows:
        verdicts = [_changed(r[c]) for c in ("정답_상태", "정답_주소", "정답_상호")]
        known = [v for v in verdicts if v is not None]
        if known:
            labeled.append((r, any(known)))
    if not labeled:
        print("정답이 채워진 확인대상 행이 없다", file=sys.stderr)
        return 1

    flagged = [(r, truth) for r, truth in labeled if r["확인대상"] == "Y"]
    unflagged = [(r, truth) for r, truth in labeled if r["확인대상"] == "표본"]

    print(f"정답이 붙은 행 {len(labeled)}곳 (확인할 행 {len(rows)}곳)")
    print("  (전화 신호는 정답을 붙일 수 없어 보지 않는다)")
    group = [truth for r, truth in flagged if r["AI판정"] == "PRIORITY_CHECK"]
    if group:
        print(f"  우선확인: 실제로 바뀜 {sum(group)}/{len(group)} = {sum(group) / len(group):.0%}")
    if unflagged:
        missed = sum(truth for _, truth in unflagged)
        print(f"  신호 없음 표본: 실제로는 바뀜(놓침) {missed}/{len(unflagged)}")
    return 0


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="2차 조사 정답지 만들기·채점")
    sub = parser.add_subparsers(dest="command", required=True)
    sample_parser = sub.add_parser("sample")
    sample_parser.add_argument("--n", type=int, default=60)
    sub.add_parser("grade")
    args = parser.parse_args()
    return sample(args.n) if args.command == "sample" else grade()


if __name__ == "__main__":
    raise SystemExit(main())
