"""웹에서 관측한 값을 우리 DB 값과 비교해 분류한다 — 2차 조사의 판정 단계.

**규칙으로만 가른다.** 모델이 매긴 확신도로 가르지 않는다(멘토 결정).

    항목별로:
      관측 없음 / 전부 DB 값과 같음 → 판단 없음
      DB 와 다른 값이 있음           → 변화 — 그 값을 수정안에 담는다(여럿이면 출처가 가장 많은 값)

    가게 전체:
      수정안이 있음 → 우선확인
      없음          → 변화없음

**Signal 은 잡힌 변화 하나다.** 백엔드 `Signal` 이 "이상 징후 하나 + 근거 문장·URL 한 쌍" 구조라,
DB 와 다른 값이 잡힌 항목마다 한 행을 만든다. 근거는 그 값을 가리킨 관측 중 대표 하나이고, 그 값을
가리킨 **서로 다른 출처(도메인) 수**를 `sourceCount` 로 함께 담는다(백엔드에는 `confidence` 칸에 넣는
것으로 제안 — 모델 확신도가 아니다). 출처끼리 엇갈리면 근거 문장 끝에 표시한다. DB 값을 확인한 근거·
비교할 수 없는 근거는 이상 징후가 아니라 Signal 로 만들지 않는다 — 변화가 없으면 Signal 은 0행이다.

**신호에 등급을 두지 않는다.** 가게 정보는 어차피 담당자가 승인해야 바뀌므로, 근거가 약하다고
AI 가 걸러 낼 이유가 없다. 변화 Signal 은 전부 `SIGNAL_HIGH` 이고 `SIGNAL_LOW`·`SIGNAL_NONE` 은
쓰지 않는다(백엔드 enum 을 줄일지는 백엔드와 정한다).

**추가확인은 돌려주지 않는다.** 팀 합의로 추가확인은 "변화없는 매장 중 직접 확인한 지 90일
이상 지난 매장"이다 — 근거의 강약이 아니라 시간 기준이고, `Store.lastCheckedAt` 을 가진
백엔드가 정한다.

그라운딩 검색 결과에 이어지지 않은 관측(`Observation.sources` 가 빈 것)은 **세지도
보여주지도 않는다** — 출처를 댈 수 없는 근거는 사람이 검증할 수 없다.

**카카오맵 확인(`kakao_map.py`)은 분류를 바꾸지 않고 Signal 도 만들지 않는다.** 결과는 `StoreFinding.mapCheck`
로 따로 나가고(찾았으면 장소 링크), 찾았을 때 폐업·주소·상호 변화 Signal 에는 "지도에는 원래대로 등록되어
있음"을 달아 담당자가 다른 가게 정보인지 가리게 한다. 못 찾은 것은 폐업 근거가 아니다(영업 가게의 43% 도
못 찾는다).
"""

from __future__ import annotations

import logging
import re
from collections import Counter
from dataclasses import replace

from src.investigation.models import (
    ChangeField,
    InvestigationTarget,
    PlaceCheck,
    PlaceStatus,
    Signal,
    SignalType,
    StoreFinding,
    TaskClassification,
)
from src.investigation.web_research import Observation, ResearchResult

_FIELD_LABELS = {
    ChangeField.STATUS: "영업 상태",
    ChangeField.PHONE: "전화번호",
    ChangeField.ADDRESS: "주소",
    ChangeField.NAME: "상호명",
}

# 도로명 + 건물번호. "동성로 12", "대종로480번길 15" — 시·구 표기(대구/대구광역시)가 달라도
# 같은 주소로 본다. 도로명은 낱말 첫머리에서 시작하고(시·구가 앞에 붙지 않게), 번호 뒤에
# "가"·"동" 같은 글자가 오면 동 이름("동성로3가")이라 도로명 주소가 아니다.
_ROAD_ADDRESS = re.compile(r"(?:^|\s)([가-힣A-Za-z0-9]+(?:로|길))\s*(\d+(?:-\d+)?)(?![가-힣\d])")
# "대종로 480번길" 처럼 도로명을 띄어 쓴 것을 붙인다.
_SPLIT_ROAD = re.compile(r"(로|길)\s+(\d+번?길)")
# 상호명의 괄호 병기 — "성심당(聖心堂)", "이재모피자 (본점)", "[본점]".
_BRACKETED = re.compile(r"[(\[（【][^)\]）】]*[)\]）】]")

logger = logging.getLogger(__name__)


def _squash(text: str) -> str:
    return re.sub(r"[\s\W_]+", "", text).lower()


def comparison_key(change_field: ChangeField, value: str) -> str:
    """같은 값인지 비교할 때 쓰는 열쇠. 표기 차이(공백·하이픈·시 이름·괄호 병기)를 지운다."""
    if change_field is ChangeField.PHONE:
        # DB 번호도 관측과 같은 규칙으로 — DB 에 "+82 53-…" 로 들어 있어도 같은 번호다.
        return re.sub(r"\D", "", domestic_phone(value) or value)
    if change_field is ChangeField.ADDRESS:
        road = road_address_key(value)
        return road if road else _squash(value)
    if change_field is ChangeField.STATUS:
        return value.upper()
    # 괄호를 지우고 나면 아무것도 안 남는 이름("(주)")은 원래대로 비교한다.
    return _squash(_BRACKETED.sub("", value)) or _squash(value)


def road_address_key(value: str) -> str | None:
    """도로명 주소의 "도로명+건물번호". 도로명 주소가 아니면(지번 주소 등) None."""
    road = _ROAD_ADDRESS.search(_SPLIT_ROAD.sub(r"\1\2", value))
    return f"{road.group(1)}{road.group(2)}" if road else None


def _comparable(change_field: ChangeField, current: str | None, observed: str) -> bool:
    """이 관측을 DB 값과 비교할 수 있는가.

    DB 가 도로명 주소인데 웹에서 지번 주소를 봤으면 같은 곳인지 가릴 수 없다 — 다르다고
    세면 멀쩡한 가게가 "주소 엇갈림"이 된다(실측: 삼송빵집 "동성로3가 1-3").
    """
    if change_field is ChangeField.ADDRESS and current and road_address_key(current):
        return road_address_key(observed) is not None
    return True


# 전화번호 하나 — (국가번호 또는 0)지역번호-국번-번호. 앞뒤에 숫자가 붙어 있으면 번호가 아니다.
# 자유롭게 숫자를 모으면 "053-111-2222 (2층)" 이 "053-1112-2222" 가 된다 — 번호가 조용히 바뀐다.
_PHONE = re.compile(
    r"(?<!\d)(?P<intl>\+\s*82[\s.-]*(?:\(0\))?\s*|\(?0)"
    r"(?P<area>\d{1,3})\)?[\s.-]*(?P<mid>\d{3,4})[\s.-]*(?P<last>\d{4})(?!\d)"
)
# 국내 전화번호의 앞자리와 있을 수 있는 자릿수. 잘린 번호("0507-5256-540", 실측)를 거른다.
_PHONE_PREFIXES = (
    ("02", (9, 10)),
    ("0507", (12,)),  # 네이버 스마트콜 0507-XXXX-XXXX
    ("050", (11, 12)),  # 0502~0508 안심번호
    ("010", (11,)),
    ("070", (11,)),
    ("03", (10, 11)),
    ("04", (10, 11)),
    ("05", (10, 11)),
    ("06", (10, 11)),
)


def _format_phone(digits: str) -> str | None:
    """숫자만 남은 국내 번호를 "053-111-2222" 꼴로 적는다. 국내 번호가 될 수 없으면 None."""
    for prefix, lengths in _PHONE_PREFIXES:
        if digits.startswith(prefix):
            if len(digits) not in lengths:
                return None
            head = 2 if prefix == "02" else 4 if prefix.startswith("050") else 3
            return f"{digits[:head]}-{digits[head:-4]}-{digits[-4:]}"
    return None


def domestic_phone(value: str) -> str | None:
    """관측한 전화번호에서 번호 하나를 꺼내 국내 표기("053-111-2222")로 고친다. 가게 번호가 될 수 없으면 None.

    - "+82 53-981-1021"(국제 표기), "(053) 111-2222", "053-111-2222 (예약)"(설명이 붙음) 모두 "053-…" 로.
      수정안에 오르는 값이라 담당자가 그대로 반영할 수 있는 모양이어야 한다(간이 DB 실측)
    - 번호가 여럿이면 첫 번째로 쓸 수 있는 것
    - 15xx·16xx·18xx 전국 대표번호는 None — 본사·시설 번호라 가게 번호가 아니다
      (실측: 롯데시네마 안 매점에 영화관 대표번호 1544-8855 를 제안)
    """
    for match in _PHONE.finditer(value):
        digits = "0" + match["area"] + match["mid"] + match["last"]
        if formatted := _format_phone(digits):
            return formatted
    return None


def _is_change(change_field: ChangeField, current: str | None, key: str) -> bool:
    """DB 와 다른 이 값을 변화로 셀 것인가.

    **DB 상태를 모르는데(UNKNOWN·없음) 웹에서 OPEN 을 봤으면 변화가 아니다.** 바뀐 것이 아니라
    확인한 것이고, 근거도 약하다 — 실측에서 OPEN 근거는 거의 전부 목록 사이트의 "영업시간"
    문장이었고, 그런 페이지는 폐업 뒤에도 남는다. 운영 DB 는 상태가 전부 UNKNOWN 이라, 세면
    우선확인이 "영업시간이 적힌 가게"로 가득 찬다(간이 DB 60곳: 우선확인 6건이 모두 이것,
    그중 1건은 폐업 가게). CLOSED·SUSPENDED 는 그대로 센다.

    변화로 세지 않을 뿐 **엇갈림 판단에는 남긴다** — 폐업 1곳·영업 2곳이면 폐업 제안에
    "다른 값을 가리키는 출처도 있음"이 붙어야 한다.
    """
    return not (change_field is ChangeField.STATUS and current in (None, "UNKNOWN") and key == "OPEN")


def _same_as_current(change_field: ChangeField, current_key: str | None, key: str) -> bool:
    """DB 값과 같은 값인가. 상호명은 한쪽이 다른 쪽을 품으면 같다고 본다
    ("삼송빵집" ⊂ "삼송빵집 본점") — 지점 표기 차이를 상호 변경으로 세지 않는다."""
    if current_key is None:
        return False
    if change_field is ChangeField.NAME:
        return bool(key) and (key in current_key or current_key in key)
    return key == current_key


def _current_value(target: InvestigationTarget, change_field: ChangeField) -> str | None:
    if change_field is ChangeField.STATUS:
        return target.internal_status.value if target.internal_status else None
    if change_field is ChangeField.ADDRESS:
        return target.address
    return getattr(target, change_field.name.lower())


def _judge_field(
    target: InvestigationTarget,
    change_field: ChangeField,
    observations: list[Observation],
    *,
    found_on_map: bool,
) -> Signal | None:
    """항목 하나를 판정한다. DB 와 다른 값(변화)이 있으면 그 변화 하나를 Signal 로, 없으면 None.

    값이 여럿이면 서로 다른 출처(도메인)가 가장 많은 값을 제안한다. 근거는 그 값을 가리킨 관측 중
    대표 하나이고, 그 값을 가리킨 출처 수를 `sourceCount` 로 담는다.
    """
    current = _current_value(target, change_field)
    current_key = comparison_key(change_field, current) if current else None

    groups: dict[str, list[Observation]] = {}
    for o in observations:
        if _comparable(change_field, current, o.value):
            groups.setdefault(comparison_key(change_field, o.value), []).append(o)

    differing = {
        k: v
        for k, v in groups.items()
        if not _same_as_current(change_field, current_key, k) and _is_change(change_field, current, k)
    }
    if not differing:
        return None

    def domains(group: list[Observation]) -> set[str]:
        return {d for o in group for d in o.domains}

    best = max(differing.values(), key=lambda g: len(domains(g)))
    # 다른 값을 가리키는 출처가 있거나, 지금 값이 맞다는 출처(상태를 모를 때의 OPEN 포함)가 있으면 엇갈린 것이다.
    conflicted = len(groups) > 1

    shown = _representative(best)
    # 백엔드 Signal 에는 출처 수 칸이 없어 문구에 적는다 — 담당자가 근거의 무게를 가늠하는 값이다.
    text = f"{_FIELD_LABELS[change_field]}: {shown.evidence} (출처 {len(domains(best))}곳)"
    if conflicted:
        text += " (다른 값을 가리키는 출처도 있음)"
    if found_on_map and _contradicts_map(target, change_field, shown.value):
        text += _MAP_DISAGREES
    return Signal(
        signal_type=SignalType.SIGNAL_HIGH,
        field=change_field,
        observed=shown.value,
        evidence_text=text,
        evidence_url=shown.sources[0].url,
        source_count=len(domains(best)),
    )


def _representative(group: list[Observation]) -> Observation:
    """같은 값을 가리키는 관측 중 수정안에 올릴 표기를 고른다 — 가장 많이 나온 표기, 같으면 짧은 것.

    같은 주소도 "중앙대로 397", "중앙대로 397 (동성로3가 1-3)" 처럼 여러 표기로 온다.
    담당자가 그대로 반영할 값이라 군더더기가 적은 쪽이 낫다.
    """
    counts = Counter(o.value for o in group)
    value = min(counts, key=lambda v: (-counts[v], len(v)))
    return next(o for o in group if o.value == value)


#: 지도에 원래대로 등록된 가게에서, 지도와 어긋나는 변화에 다는 표시.
_MAP_DISAGREES = " (카카오맵에는 DB 주소·상호로 영업 중인 가게가 있음 — 다른 가게 정보일 수 있음, 또는 지도가 아직 안 바뀜)"


def _contradicts_map(target: InvestigationTarget, change_field: ChangeField, observed: str) -> bool:
    """지도에 같은 자리·같은 상호로 있는데 그와 어긋나는 변화인가.

    - 전화번호는 지도와 어긋나지 않는다
    - 주소는 DB 에 도로명 주소가 있을 때만 어긋난다 — DB 가 지번뿐이면 웹의 도로명 주소는 같은 자리를
      채우는 것이다(간이 DB 회귀: 크라운호프 "두산동 660" → "수성못6길 6", 지도에서도 그 자리에 있음)
    """
    if change_field is ChangeField.STATUS:
        return observed in ("CLOSED", "SUSPENDED")
    if change_field is ChangeField.ADDRESS:
        return bool(target.address and road_address_key(target.address))
    return change_field is ChangeField.NAME


def classify(
    target: InvestigationTarget, result: ResearchResult, place: PlaceCheck | None = None
) -> StoreFinding:
    """관측 결과를 `StoreFinding` 으로 만든다. 수정안은 제안일 뿐 반영하지 않는다."""
    grounded = [o for o in result.observations if o.sources]
    if len(grounded) < len(result.observations):
        logger.info(
            "출처가 이어지지 않아 뺀 관측 %s건 (storeId=%s)",
            len(result.observations) - len(grounded),
            target.store_id,
        )

    # 전화번호는 국내 표기로 고치고, 번호가 될 수 없는 값(잘린 번호 등)은 판정에서 뺀다.
    judged = []
    for o in grounded:
        if o.field is ChangeField.PHONE:
            phone = domestic_phone(o.value)
            if phone is None:
                continue
            o = replace(o, value=phone)
        judged.append(o)

    found_on_map = place is not None and place.status is PlaceStatus.FOUND
    signals = [
        signal
        for change_field in ChangeField
        if (
            signal := _judge_field(
                target, change_field, [o for o in judged if o.field is change_field], found_on_map=found_on_map
            )
        )
    ]
    return StoreFinding(
        store_id=target.store_id,
        classification=TaskClassification.PRIORITY_CHECK if signals else TaskClassification.NO_CHANGE,
        proposed_changes={s.field.value: s.observed for s in signals},
        signals=signals,
        map_check=place,
    )
