"""백엔드 Java enum과 우리 파이썬 enum이 같은 값을 쓰는지 대조한다.

손으로 옮긴 타입은 조용히 어긋난다. 5주차에 가게 상태가, 6주차에 Task 분류가 그렇게
갈라졌고 둘 다 멘토 리뷰에서 지적받았다. 같은 리포에 백엔드 소스가 있으니
**원본을 직접 읽어서** 대조한다.

백엔드가 값을 바꾸면 이 테스트가 깨진다. 그게 목적이다.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from src.backend_client.client import MAX_LIMIT
from src.backend_client.models import (
    BusinessState,
    NtsCheckFilter,
    NtsLookupResult,
    StatusComparison,
    StoreStatus,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
JAVA_ROOT = REPO_ROOT / "backend/src/main/java/com/ktc4/backend/domain"

# enum 상수 한 줄. **마지막 상수에는 쉼표가 없다** — `UNKNOWN     // 미확인` 처럼 온다.
# 그래서 구분자를 선택으로 두고, 상수 목록이 끝나는 지점은 아래 루프에서 따로 판단한다.
ENUM_CONSTANT = re.compile(r"^\s+([A-Z][A-Z0-9_]*)\s*(\(|,|;|//|$)")
ENUM_DECLARATION = re.compile(r"\benum\s+[A-Za-z_]\w*\s*\{")

CASES = [
    ("store/enums/StoreStatus.java", StoreStatus),
    ("store/enums/StatusComparison.java", StatusComparison),
    ("store/enums/NtsLookupResult.java", NtsLookupResult),
    ("business/enums/BusinessState.java", BusinessState),
    # PR #32 가 머지되어야 생기는 파일이다. 그전까지는 skip 된다.
    ("store/enums/NtsCheckFilter.java", NtsCheckFilter),
]

# 백엔드 컨트롤러가 받아 주는 limit 상한. 우리가 이 값을 복제해 들고 있다.
MAX_LIMIT_IN_JAVA = re.compile(r"MAX_LIMIT\s*=\s*(\d+)")


def java_enum_values(path: Path) -> list[str]:
    """`enum X {` 다음부터 상수 목록이 끝날 때까지만 읽는다.

    상수 목록은 `;` 또는 `}`에서 끝난다. 그 뒤에 오는 메서드 본문에도 대문자 식별자가
    있을 수 있어(`BusinessState.fromCode`의 `NOT_REGISTERED` 반환 등) 범위를 끊어야 한다.
    """
    values: list[str] = []
    started = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if not started:
            started = bool(ENUM_DECLARATION.search(line))
            continue
        m = ENUM_CONSTANT.match(line)
        if m:
            values.append(m.group(1))
        stripped = line.strip()
        if values and (stripped.endswith(";") or stripped.startswith("}")):
            break
    return values


@pytest.mark.parametrize("relative_path,py_enum", CASES, ids=[c[0] for c in CASES])
def test_enum_matches_backend(relative_path: str, py_enum: type) -> None:
    java_file = JAVA_ROOT / relative_path
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    java_values = java_enum_values(java_file)
    assert java_values, f"enum 상수를 못 찾았습니다: {java_file}"
    assert [e.value for e in py_enum] == java_values, (
        f"{py_enum.__name__}이 백엔드와 다릅니다.\n"
        f"  백엔드: {java_values}\n"
        f"  파이썬: {[e.value for e in py_enum]}"
    )


def test_max_limit_matches_backend() -> None:
    """`limit` 상한을 우리가 복제해 들고 있다 — 백엔드가 바꾸면 여기서 깨져야 한다."""
    controller = JAVA_ROOT / "store/controller/StoreController.java"
    if not controller.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {controller}")

    m = MAX_LIMIT_IN_JAVA.search(controller.read_text(encoding="utf-8"))
    assert m, "StoreController 에서 MAX_LIMIT 를 찾지 못했습니다"
    assert int(m.group(1)) == MAX_LIMIT, (
        f"limit 상한이 백엔드와 다릅니다. 백엔드={m.group(1)} 파이썬={MAX_LIMIT}"
    )
