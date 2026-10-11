"""백엔드 역할을 흉내 내어 연동 흐름을 끝까지 돌려 본다 — **검증용 하네스**.

백엔드에 AI 를 부르는 코드가 아직 없다(PROMPT-106). 그래서 그 자리를 이 스크립트가
대신해 **백엔드가 할 일을 그대로** 한다.

    1. 백엔드 `GET /api/stores/nts-checks` 로 행을 읽는다 (또는 목업 CSV)
    2. 2차 조사 대상만 고른다
    3. `POST {AI}/investigations` 로 **행을 그대로** 던진다
    4. 응답을 백엔드가 저장할 모양(Task·Signal)으로 풀어 본다

**운영 코드가 아니다.** 백엔드가 실제로 호출하게 되면 이 스크립트는 지워도 된다.
남겨 두는 이유는 계약이 바뀔 때 손으로 다시 확인할 수 있어야 해서다.

    uv run python -m eval.e2e_flow --backend http://localhost:8080 --ai http://localhost:8000
    uv run python -m eval.e2e_flow --csv eval/fixtures/mock_nts_checks.csv --ai http://localhost:8000
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.backend_client import BackendClient  # noqa: E402
from src.backend_client.models import StoreCheck  # noqa: E402
from eval import mock_db  # noqa: E402


def rows_from_backend(base_url: str, limit: int) -> list[dict]:
    client = BackendClient(base_url)
    try:
        page = client.get_nts_checks(page=0, limit=limit)
    finally:
        client.close()
    # mode="json" 이어야 datetime 이 문자열이 된다. 실제 백엔드는 JSON 으로 주므로
    # 그 모양을 그대로 흉내 내야 "행을 그대로 던진다"가 참이 된다.
    return [c.model_dump(by_alias=True, mode="json") for c in page.content]


def rows_from_csv(path: Path, limit: int) -> list[dict]:
    with path.open(encoding="utf-8-sig") as f:
        raw = list(csv.DictReader(f))[:limit]
    # 정답(answer_*)은 백엔드가 보내지 않는 칸이다. 빼고 던져야 진짜 흐름과 같다.
    return [
        {k: (None if v == "" else v) for k, v in r.items() if not k.startswith("answer_")}
        for r in raw
    ]


def as_backend_would_store(finding: dict) -> dict:
    """백엔드가 이 응답으로 무엇을 만드는가 — `docs/백엔드_연동.md` 의 표 그대로."""
    if finding.get("failure"):
        return {"저장": "없음", "Job.errorMessage": finding["failure"]}
    return {
        "Task.classification": finding.get("classification"),
        "Task.proposedChanges": finding.get("proposedChanges"),
        "Signal 행": [
            {
                "signalType": s.get("signalType"),
                "field": s.get("field"),
                "confidence": s.get("confidence"),
                "evidenceText": (s.get("evidenceText") or "")[:60],
                "evidenceUrl": s.get("evidenceUrl"),
                # source 는 AI 가 보내지 않는다. 백엔드가 AI_WEB 으로 채운다.
                "source": "AI_WEB (백엔드가 채움)",
            }
            for s in finding.get("signals", [])
        ],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ai", default="http://localhost:8000")
    ap.add_argument("--backend")
    ap.add_argument("--csv", type=Path)
    ap.add_argument("--limit", type=int, default=3)
    ap.add_argument("--show", action="store_true", help="응답 전문을 찍는다")
    args = ap.parse_args()

    if not args.backend and not args.csv:
        print("--backend 또는 --csv 중 하나는 있어야 합니다", file=sys.stderr)
        return 1

    print("── 1. 행 읽기")
    if args.backend:
        rows = rows_from_backend(args.backend, args.limit)
        print(f"   백엔드 {args.backend} 에서 {len(rows)}건")
        if not rows:
            print("   ⚠️ 0건이다. 가게 데이터가 없으면 흐름을 돌릴 수 없다.")
            return 1
    else:
        rows = rows_from_csv(args.csv, args.limit)
        print(f"   목업 {args.csv} 에서 {len(rows)}건")

    print("── 2. 2차 조사 대상 고르기 (백엔드가 거르는 자리)")
    targets = []
    for row in rows:
        try:
            check = StoreCheck.model_validate(row)
        except Exception as e:  # noqa: BLE001
            print(f"   파싱 실패 storeId={row.get('storeId')}: {e}")
            return 1
        if mock_db.is_second_round_target(check):
            targets.append(row)
    print(f"   {len(targets)}/{len(rows)}건이 대상")
    if not targets:
        return 1

    print(f"── 3. POST {args.ai}/investigations — 행을 그대로 던진다")
    res = httpx.post(f"{args.ai}/investigations", json=targets, timeout=180.0)
    print(f"   HTTP {res.status_code}")
    if res.status_code != 200:
        print(f"   {res.text[:300]}")
        return 1
    body = res.json()
    print(f"   requested={body['requested']} succeeded={body['succeeded']} results={len(body['results'])}")
    assert len(body["results"]) == body["requested"], "결과 수가 요청 수와 다르다"

    print("── 4. 백엔드가 저장할 모양으로 풀기")
    for finding in body["results"]:
        print(f"   storeId={finding['storeId']}")
        print("   " + json.dumps(as_backend_would_store(finding), ensure_ascii=False, indent=4)[:400])
    if args.show:
        print(json.dumps(body, ensure_ascii=False, indent=2))
    print("\n✅ 연동 흐름이 끝까지 통과했습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
