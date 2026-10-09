#!/usr/bin/env python3
"""AI 서버가 내려주는 응답이 백엔드에 제대로 맵핑돼 있는지 대조한다.

9주차 멘토 리뷰 🟡4 에서 받은 숙제다. `failure` 칸이 AI 는 객체, 백엔드는 String 이었는데
양쪽 테스트 어디에서도 걸리지 않았다. 이름이 맞는지만 보고 **모양을 안 봤기 때문**이다.

그래서 이 스크립트는 세 가지를 따로 본다.

1. **있나** — AI 가 보내는 칸이 백엔드에 있는가
2. **모양이 같나** — 객체를 보내는데 String 으로 받고 있지 않은가 (이게 빠져 있던 검사다)
3. **누가 보고 있나** — 그 칸을 검사하는 테스트가 실제로 있는가. 없으면 사각지대다

사용법:
    ai/.venv/bin/python .claude/skills/계약-대조/check_contract.py
    ai/.venv/bin/python .claude/skills/계약-대조/check_contract.py --gen-test   # 사각지대용 테스트 뼈대 출력

리포지토리 루트에서 돈다. 백엔드 소스가 없으면 그 쌍만 건너뛴다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
AI_ROOT = ROOT / "ai"
JAVA_ROOT = ROOT / "backend/src/main/java/com/ktc4/backend/domain"
FE_GENERATED = ROOT / "frontend/src/api/generated"
CONTRACT_TEST = AI_ROOT / "tests/backend_client/test_contract.py"

# AI 모델 ↔ 백엔드 자바 타입. (AI 모델 이름, 샘플 만들 kwargs, 자바 파일, 자바 쪽 이름)
# 자바 쪽 이름이 None 이면 `private X y;` 형태의 엔티티로 읽는다.
PAIRS = [
    ("StoreFinding", {"storeId": 1}, "investigation/client/AiInvestigationResponse.java", "Result"),
    ("Signal", {"signalType": "SIGNAL_HIGH", "field": "phone", "observed": "053-000-0000",
                "evidenceText": "근거", "evidenceUrl": "https://example.com", "sourceCount": 2},
     "investigation/client/AiInvestigationResponse.java", "SignalItem"),
    ("InvestigationResponse", {"results": [], "requested": 0, "succeeded": 0},
     "investigation/client/AiInvestigationResponse.java", "AiInvestigationResponse"),
]

NESTED_RECORD = re.compile(r"\brecord\s+(\w+)\s*\(([^)]*)\)", re.DOTALL)
RECORD_PARAM = re.compile(r"([\w.]+(?:<[^>]*>)?)\s+(\w+)\s*$")
ENTITY_FIELD = re.compile(r"^\s*private\s+([\w<>,\[\]\s]+?)\s+(\w+)\s*;", re.MULTILINE)
# 계약 테스트가 비교에서 빼는 칸: `sent -= {"a", "b"}`
EXCLUDED = re.compile(r"sent\s*-=\s*\{([^}]*)\}")

SCALAR_JAVA = {"String", "Long", "Integer", "Double", "Boolean", "int", "long", "double", "boolean"}


def split_params(params: str) -> list[str]:
    """record 구성요소를 쉼표로 나눈다. `Map<String, String>` 안의 쉼표는 세지 않는다.

    `<>` 깊이를 보지 않고 그냥 split(",") 하면 제네릭이 두 토막으로 쪼개져
    "백엔드에 칸이 없습니다" 라는 거짓 보고가 나온다. 처음 짤 때 그렇게 틀렸다.
    """
    out, depth, buf = [], 0, []
    for ch in params:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(buf))
            buf = []
            continue
        buf.append(ch)
    if buf:
        out.append("".join(buf))
    return [p.strip() for p in out if p.strip()]


def java_members(path: Path, name: str) -> dict[str, str]:
    """자바 파일에서 `{칸 이름: 타입}` 을 뽑는다. record 와 엔티티 둘 다 읽는다."""
    text = path.read_text(encoding="utf-8")
    for m in NESTED_RECORD.finditer(text):
        if m.group(1) != name:
            continue
        out: dict[str, str] = {}
        for part in split_params(m.group(2)):
            if (pm := RECORD_PARAM.match(part)) is not None:
                out[pm.group(2)] = pm.group(1)
        return out
    return {f: t.strip() for t, f in ENTITY_FIELD.findall(text)}


def ai_shape(model_name: str, kwargs: dict) -> dict[str, str]:
    """AI 가 **실제로 내보내는** 모양. 스키마가 아니라 직렬화 결과를 본다.

    `exclude=True` 인 칸은 스키마에는 남아 있지만 응답에는 안 나간다. 스키마를 보면 틀린다.
    """
    from src.investigation import models as m

    model = getattr(m, model_name)
    sent = json.loads(model(**kwargs).model_dump_json(by_alias=True))
    out = {}
    for k, v in sent.items():
        if isinstance(v, dict):
            out[k] = f"객체{{{', '.join(sorted(v))}}}"
        elif isinstance(v, list):
            out[k] = "배열"
        elif v is None:
            out[k] = "null(모양 미정)"
        else:
            out[k] = type(v).__name__
    return out


def excluded_fields() -> set[str]:
    """계약 테스트가 **이름 비교**에서 뺀 칸."""
    if not CONTRACT_TEST.exists():
        return set()
    found: set[str] = set()
    for m in EXCLUDED.finditer(CONTRACT_TEST.read_text(encoding="utf-8")):
        found |= set(re.findall(r'"(\w+)"', m.group(1)))
    return found


def shape_tested() -> set[str]:
    """그 칸의 **모양**을 따로 보는 테스트가 있는 칸.

    이름 비교에서 빼는 것 자체는 틀린 게 아니다(`storeId` 는 열쇠, `failure` 는
    Job.errorMessage 로 간다). 사각지대인지 가르는 기준은 **모양을 보는 곳이 있는가**다.
    """
    if not CONTRACT_TEST.exists():
        return set()
    text = CONTRACT_TEST.read_text(encoding="utf-8")
    return set(re.findall(r"def test_(\w+?)_모양이", text))


def shape_conflict(ai: str, java: str) -> str | None:
    """모양이 어긋나면 이유를, 맞으면 None."""
    if ai.startswith("객체") and java in SCALAR_JAVA:
        return f"AI 는 {ai} 를 보내는데 백엔드는 {java} 로 받습니다 (Jackson 이 못 넣습니다)"
    if ai == "str" and java not in SCALAR_JAVA and not java.startswith("Map"):
        return f"AI 는 문자열을 보내는데 백엔드는 {java} 로 받습니다"
    if ai == "배열" and not (java.startswith("List") or java.startswith("Set")):
        return f"AI 는 배열을 보내는데 백엔드는 {java} 로 받습니다"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gen-test", action="store_true", help="사각지대용 테스트 뼈대를 출력한다")
    args = ap.parse_args()

    sys.path.insert(0, str(AI_ROOT))
    skipped = excluded_fields()
    tested = shape_tested()
    problems: list[tuple[str, str, str]] = []   # (모델, 칸, 사유)
    blind: list[tuple[str, str]] = []           # (모델, 칸)

    for model_name, kwargs, rel, java_name in PAIRS:
        java_file = JAVA_ROOT / rel
        print(f"\n=== {model_name}  ↔  {rel}#{java_name}")
        if not java_file.exists():
            print("   건너뜀 — 백엔드 소스가 없습니다")
            continue

        ai = ai_shape(model_name, kwargs)
        be = java_members(java_file, java_name)
        if not be:
            print(f"   ✗ 자바에서 `{java_name}` 을 찾지 못했습니다")
            continue

        for field, ai_type in sorted(ai.items()):
            java_type = be.get(field)
            if java_type is None:
                print(f"   ✗ {field:18} {ai_type:22} → 백엔드에 칸이 없습니다")
                problems.append((model_name, field, "백엔드에 칸이 없습니다"))
                continue
            why = shape_conflict(ai_type, java_type)
            # 이름 비교에서 뺐는데 모양을 보는 테스트도 없으면 사각지대다.
            # 빼는 것 자체는 틀린 게 아니다 — 모양을 보는 곳이 없는 게 문제다.
            is_blind = field in skipped and field not in tested
            mark = "✗" if why else ("△" if is_blind else "✓")
            note = why or ("이름 비교에서 뺐고 모양을 보는 곳도 없습니다 (사각지대)" if is_blind
                           else ("모양 검사 있음" if field in tested else ""))
            print(f"   {mark} {field:18} {ai_type:22} → {java_type:22} {note}")
            if why:
                problems.append((model_name, field, why))
            if is_blind:
                blind.append((model_name, field))

    print(f"\n=== 프론트 (AI → BE → FE 중 마지막 층)")
    if FE_GENERATED.exists():
        print(f"   {FE_GENERATED.relative_to(ROOT)} 있음 — 대조 가능")
    else:
        print(f"   ✗ {FE_GENERATED.relative_to(ROOT)} 가 없습니다.")
        print("     Orval 생성 코드를 커밋하지 않아 **정적으로 대조할 수 없습니다.**")
        print("     9주차 멘토 리뷰 🟢6(생성 코드 커밋)이 먼저 들어가야 이 층을 검사할 수 있습니다.")

    print(f"\n{'='*70}")
    print(f"어긋난 칸 {len(problems)}개 / 테스트가 안 보는 칸 {len(blind)}개")
    for model, field, why in problems:
        print(f"  ✗ {model}.{field} — {why}")
    for model, field in blind:
        print(f"  △ {model}.{field} — 계약 테스트가 비교에서 뺀 칸입니다")

    if args.gen_test and (problems or blind):
        print(f"\n{'='*70}\n# 아래를 ai/tests/backend_client/test_contract.py 에 붙이세요\n")
        for model, field, *_ in [(m, f, None) for m, f, _ in problems] + [(m, f, None) for m, f in blind]:
            print(f'''
def test_{field}_모양이_양쪽에서_같다() -> None:
    """{model}.{field} 의 모양을 본다. 이름만 맞는지가 아니라."""
    java_file = JAVA_ROOT / "investigation/client/AiInvestigationResponse.java"
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {{java_file}}")
    # TODO: java_nested_record(java_file, "<record 이름>") 로 타입을 꺼내
    #       AI 쪽 직렬화 결과와 모양을 비교한다.
''')

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
