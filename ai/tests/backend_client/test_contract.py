"""백엔드 Java enum과 우리 파이썬 enum이 같은 값을 쓰는지 대조한다.

손으로 옮긴 타입은 조용히 어긋난다. 5주차에 가게 상태가, 6주차에 Task 분류가 그렇게
갈라졌고 둘 다 멘토 리뷰에서 지적받았다. 같은 리포에 백엔드 소스가 있으니
**원본을 직접 읽어서** 대조한다.

백엔드가 값을 바꾸면 이 테스트가 깨진다. 그게 목적이다.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from pydantic import BaseModel

from src.backend_client.client import MAX_LIMIT
from src.backend_client.models import (
    Store,
    StoreCheck,
    BusinessState,
    NtsCheckFilter,
    NtsLookupResult,
    StatusComparison,
    StoreStatus,
)
from src.investigation.models import ChangeField, SignalType, TaskClassification

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
    # 2차 조사 결과가 백엔드 Task·Signal 로 저장되는 값이다.
    ("task/enums/TaskClassification.java", TaskClassification),
    ("signal/enums/SignalType.java", SignalType),
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


# Java record 의 구성요소 한 줄: `        StoreStatus internalStatus,`
# 마지막 구성요소 뒤에는 쉼표도 괄호도 없다 (`LocalDateTime ntsCheckedAt` 다음 줄이 `) {`).
RECORD_COMPONENT = re.compile(r"^\s+[A-Za-z][\w<>,\s]*\s+([a-z]\w*)\s*[,)]?\s*$")

FIELD_CASES = [
    ("store/dto/StoreResponse.java", Store),
    ("store/dto/StoreCheckResponse.java", StoreCheck),
]


def java_record_fields(path: Path) -> list[str]:
    """`public record X(...)` 의 구성요소 이름을 순서대로 뽑는다."""
    text = path.read_text(encoding="utf-8")
    start = text.index("public record")
    body = text[start : text.index(") {", start)]
    return [m.group(1) for line in body.splitlines() if (m := RECORD_COMPONENT.match(line))]


@pytest.mark.parametrize("relative_path,model", FIELD_CASES, ids=[c[0] for c in FIELD_CASES])
def test_required_fields_exist_in_backend(relative_path: str, model: type[BaseModel]) -> None:
    """우리가 **필수로 요구하는** 필드가 백엔드 응답에 실제로 있는가.

    없으면 응답 파싱이 통째로 실패한다. 반대로 우리가 선택(Optional)으로 둔 필드는
    백엔드에 없어도 None 으로 넘어가므로 검사하지 않는다.

    필드 **이름**까지 대조하는 건 enum 값 대조로 못 잡는 구멍이었다. 다만 백엔드를
    띄워 실제 JSON 을 받아보는 것과는 다르다 — 직렬화 설정(@JsonProperty 등)까지는 못 본다.
    """
    java_file = JAVA_ROOT / relative_path
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    java_fields = set(java_record_fields(java_file))
    assert java_fields, f"record 구성요소를 못 찾았습니다: {java_file}"

    required = {
        (f.alias or name) for name, f in model.model_fields.items() if f.is_required()
    }
    missing = required - java_fields
    # PR #32 가 들어오기 전에는 #32 에서 생기는 필드가 없는 게 정상이다. 그 필드들만 빼고
    # **나머지는 그대로 검사한다** — "#32 미머지"를 이유로 통째로 건너뛰면 다른 필드가
    # 빠지거나 이름이 바뀐 것까지 같이 가려진다.
    pending = {"dataProblem", "ntsCheckedAt", "lat", "lng"}
    if missing & pending:
        missing -= pending
        if not missing:
            pytest.skip("PR #32 미머지 — #32 에서 생기는 필드만 빠져 있다. 머지되면 자동으로 켜진다.")
    assert not missing, (
        f"{model.__name__} 이 필수로 요구하는 필드가 백엔드에 없습니다: {sorted(missing)}\n"
        f"  백엔드: {sorted(java_fields)}"
    )


# `ChangeField` 는 위 CASES 로 대조할 수 없다 — **상수 이름이 일부러 다르다.**
# 백엔드는 DB 에 상수 이름으로 저장하고(`@Enumerated(STRING)`), AI 는 `Task.proposedChanges`
# 의 키로 쓰는 Store 필드명을 값으로 보낸다(`ADDRESS` → `"addressRoad"`). 백엔드
# `ChangeField.java` 주석도 이 차이를 명시한다.
#
# 그래서 이름이 아니라 **짝이 맞는지**를 본다. 양쪽에 항목이 하나만 늘어도 깨지게 한다 —
# 이 팀은 enum 이 어긋나 두 번 데였는데(`StoreStatus`, `TASK_HIGH`↔`PRIORITY_CHECK`),
# `ChangeField` 는 그 대조 목록에 빠져 있었다.
CHANGE_FIELD_PAIRS = {
    "STATUS": "status",
    "PHONE": "phone",
    "ADDRESS_ROAD": "addressRoad",
    "NAME": "name",
}


def test_change_field_는_백엔드와_짝이_맞는다() -> None:
    java_file = JAVA_ROOT / "signal/enums/ChangeField.java"
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    java_names = set(java_enum_values(java_file))
    py_values = {m.value for m in ChangeField}

    assert java_names == set(CHANGE_FIELD_PAIRS), (
        f"백엔드 ChangeField 가 바뀌었습니다: {sorted(java_names)}. "
        "AI 쪽 ChangeField 와 이 테이블을 같이 고쳐야 합니다."
    )
    assert py_values == set(CHANGE_FIELD_PAIRS.values()), (
        f"AI ChangeField 값이 바뀌었습니다: {sorted(py_values)}. "
        "백엔드가 이 값으로 받으므로 양쪽을 같이 고쳐야 합니다."
    )


def test_change_field_값은_store_필드명이다() -> None:
    """AI 가 보내는 값은 `Store` 의 필드명이어야 한다 — `proposedChanges` 의 키와 같아야 하니까.

    백엔드 `StoreUpdateRequest` 가 그 필드명을 받는다. 여기가 어긋나면 담당자가 수정안을
    승인해도 `PATCH /api/stores/{id}` 에 실을 칸을 못 찾는다.
    """
    update_request = (
        REPO_ROOT / "backend/src/main/java/com/ktc4/backend/domain/store/dto/StoreUpdateRequest.java"
    )
    if not update_request.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {update_request}")

    source = update_request.read_text(encoding="utf-8")
    for value in (m.value for m in ChangeField):
        assert re.search(rf"\b{value}\b", source), (
            f"AI 가 보내는 ChangeField 값 '{value}' 가 StoreUpdateRequest 에 없습니다. "
            "수정안을 승인해도 반영할 칸이 없습니다."
        )


# ── AI 응답의 칸이 백엔드에 실제로 자리가 있는가 ────────────────────────────────
#
# 지금까지 enum 값만 대조했다. **칸 이름은 보지 않아서**, AI 가 보내는 칸이 백엔드에
# 없으면 연동할 때가 되어서야 안다. 문서에도 "남은 구멍"으로 적혀 있었다.
#
# 백엔드는 Java 필드명이 camelCase 이고 AI 도 alias 가 camelCase 라 그대로 견줄 수 있다.
# DTO 가 아직 없어서 엔티티(`Signal.java`·`Task.java`)를 본다 — 저장될 자리가 거기다.

JAVA_FIELD = re.compile(r"^\s*private\s+[\w<>,\[\]\s]+?\s+(\w+)\s*;", re.MULTILINE)


def java_fields(path: Path) -> set[str]:
    return set(JAVA_FIELD.findall(path.read_text(encoding="utf-8")))


def test_signal_칸이_백엔드에_있다() -> None:
    java_file = JAVA_ROOT / "signal/entity/Signal.java"
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    from src.investigation.models import ChangeField, Signal, SignalType

    # **스키마가 아니라 실제 직렬화 결과**를 본다. `observed`·`sourceCount` 는
    # `exclude=True` 라 응답에 안 나가는데, 스키마에는 그대로 남아 있다.
    sample = Signal(
        signalType=SignalType.SIGNAL_HIGH,
        field=ChangeField.PHONE,
        observed="053-000-0000",
        evidenceText="근거",
        evidenceUrl="https://example.com",
        sourceCount=2,
    )
    sent = set(json.loads(sample.model_dump_json(by_alias=True)))
    backend = java_fields(java_file)

    missing = sent - backend
    assert not missing, (
        f"AI 가 보내는 Signal 칸이 백엔드에 없습니다: {sorted(missing)}. "
        f"백엔드 Signal 엔티티: {sorted(backend)}"
    )


def test_store_finding_칸이_백엔드에_있다() -> None:
    """`storeId`·`failure` 는 Task 칸이 아니라 백엔드가 흐름에서 쓰는 값이라 뺀다.

    - `storeId` — 결과를 가게와 잇는 열쇠. Task 는 store 를 연관으로 들고 있다
    - `failure` — Task 를 만들지 않고 `Job.errorMessage` 로 간다(`docs/백엔드_연동.md`)
    """
    java_file = JAVA_ROOT / "task/entity/Task.java"
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    from src.investigation.models import StoreFinding, TaskClassification

    sample = StoreFinding(
        storeId=1,
        classification=TaskClassification.PRIORITY_CHECK,
        proposedChanges={"phone": "053-000-0000"},
    )
    sent = set(json.loads(sample.model_dump_json(by_alias=True)))
    sent -= {"storeId", "failure", "signals"}
    backend = java_fields(java_file)

    missing = sent - backend
    assert not missing, (
        f"AI 가 보내는 StoreFinding 칸이 백엔드 Task 에 없습니다: {sorted(missing)}. "
        f"백엔드 Task 엔티티: {sorted(backend)}"
    )


# `record Result(` 또는 `record SignalItem(` 처럼 **중첩 record** 의 구성요소를 읽는다.
# `java_record_fields` 는 `public record` 하나만 보므로 여기서는 이름으로 찾는다.
NESTED_RECORD = re.compile(r"\brecord\s+(\w+)\s*\(([^)]*)\)", re.DOTALL)
# `Long storeId` · `Map<String, String> proposedChanges` · `String failure`
RECORD_PARAM = re.compile(r"([\w.]+(?:<[^>]*>)?)\s+(\w+)\s*$")


def java_nested_record(path: Path, name: str) -> dict[str, str]:
    """중첩 record 하나의 `{구성요소 이름: 자바 타입}` 을 준다."""
    text = path.read_text(encoding="utf-8")
    for m in NESTED_RECORD.finditer(text):
        if m.group(1) != name:
            continue
        out: dict[str, str] = {}
        for part in m.group(2).split(","):
            part = part.strip()
            if (pm := RECORD_PARAM.match(part)) is not None:
                out[pm.group(2)] = pm.group(1)
        return out
    return {}


def test_failure_모양이_양쪽에서_같다() -> None:
    """`failure` 칸의 **모양**을 검사한다 — 이름만 맞는지가 아니라.

    `test_store_finding_칸이_백엔드에_있다` 가 `failure` 를 비교에서 빼는 것은 맞다.
    그 칸은 `Task` 가 아니라 `Job.errorMessage` 로 간다. 그런데 그래서 **어느 테스트도
    이 칸의 모양을 보지 않았고**, AI 는 객체로 보내고 백엔드는 `String` 으로 받는 상태로
    두 PR 이 각자 머지됐다(#71 · #73). Jackson 이 객체를 `String` 에 못 넣어
    `RestClientException` → `AiContractError` 가 되고, **실패 이유가 사라진다.**

    어느 쪽으로 통일할지는 팀이 정한다. 이 테스트는 **어느 쪽이든 양쪽이 같으면 통과**한다.

    - AI 가 `Failure` 객체를 보낸다면 → 백엔드 `failure` 도 같은 칸을 가진 타입이어야 한다
    - AI 가 문자열로 평평하게 바꾼다면 → 백엔드 `failure` 는 `String` 이어야 한다
    """
    java_file = JAVA_ROOT / "investigation/client/AiInvestigationResponse.java"
    if not java_file.exists():
        pytest.skip(f"백엔드 소스가 없습니다: {java_file}")

    from src.investigation.failure import failure_of
    from src.investigation.models import StoreFinding

    result = java_nested_record(java_file, "Result")
    assert result, f"{java_file.name} 에서 `record Result(...)` 를 찾지 못했습니다"
    assert "failure" in result, (
        f"백엔드 `Result` 에 `failure` 칸이 없습니다. AI 는 실패 사유를 이 칸으로 보냅니다. "
        f"백엔드 칸: {sorted(result)}"
    )
    java_type = result["failure"]

    # AI 쪽이 객체를 보내는지 문자열을 보내는지는 **실제 직렬화 결과**로 판단한다.
    # 애너테이션을 보면 Optional 을 벗기는 일이 끼어든다.
    #
    # 값은 운영에서 쓰는 길(`failure_of`)로 만든다. 여기서 모양을 손으로 적으면
    # 그 모양이 아닐 때 **검사하기 전에 모델 검증에서 먼저 터져** 무엇이 어긋났는지
    # 대신 ValidationError 를 보게 된다. 실제로 PROMPT-125 를 평평하게 고칠 때 그랬다.
    sample = StoreFinding(storeId=1, failure=failure_of(ValueError("조사 실패")))
    sent_failure = json.loads(sample.model_dump_json(by_alias=True))["failure"]

    if isinstance(sent_failure, str):
        assert java_type == "String", (
            f"AI 는 `failure` 를 문자열로 보내는데 백엔드는 `{java_type}` 로 받습니다."
        )
        return

    assert java_type != "String", (
        f"AI 는 `failure` 를 객체로 보내는데(칸: {sorted(sent_failure)}) "
        f"백엔드는 `String` 으로 받습니다. Jackson 이 객체를 String 에 넣지 못해 "
        f"AiContractError 가 되고 실패 이유가 사라집니다. "
        f"어느 쪽으로 통일할지 정한 뒤 양쪽을 맞춰 주세요 — PROMPT-125."
    )

    backend_failure = java_nested_record(java_file, java_type)
    assert backend_failure, (
        f"백엔드 `failure` 타입이 `{java_type}` 인데 그 record 를 "
        f"{java_file.name} 에서 찾지 못했습니다"
    )
    missing = set(sent_failure) - set(backend_failure)
    assert not missing, (
        f"AI 가 보내는 `failure` 칸이 백엔드 `{java_type}` 에 없습니다: {sorted(missing)}. "
        f"백엔드 칸: {sorted(backend_failure)}"
    )
