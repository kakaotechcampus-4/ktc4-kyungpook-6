"""간이 DB — 운영에서 2차 조사로 넘어올 가게 행을 인허가 데이터로 흉내 낸다.

실데이터가 아직 DB 에 없다. 서비스 대상은 선한영향력가게라 **입력 모양과 결함은 선한영향력가게
명단을 따르고**, 가게와 정답(폐업·영업)은 인허가(LOCALDATA)에서 가져온다 — 영업현황 조사는
일반 가게로 시험해도 된다.

선한영향력가게 명단(경기 883건)을 백엔드에 넣으면 이렇게 된다:

    전화번호   컬럼 없음      → phone = null (전부)
    영업상태   컬럼 없음      → status = UNKNOWN (`Store` 기본값, 전부)
    도로명주소 90% 만 있음    → 나머지는 지번 주소가 addressRoad 에 들어간다고 본다
    사업자번호 54%            → 번호가 없으면 dataProblem 이라 2차 조사 대상이 아니다

그래서 이 DB 의 행은 전부 **"번호는 있고 국세청은 계속사업자인데, 우리 상태가 UNKNOWN 이라
비교할 수 없는 가게"** 다(`statusComparison=NOT_COMPARABLE`). 운영에서 2차 조사로 넘어올 가게의
대부분이 이 모양이다. 사업자번호는 가짜 값이다 — 인허가에는 번호가 없고 2차 조사는 번호를 쓰지 않는다.

행 모양은 백엔드 `GET /api/stores/nts-checks` 응답(`StoreCheck`)이다 — `store` 테이블(`Store.java`)
칸에 국세청 조회 칸이 붙은 것이고, `status` 는 응답에서 `internalStatus` 로 이름이 바뀐다. 정답은
`answer_*` 칸에 따로 둔다 — `InvestigationTarget`·`StoreCheck` 는 모르는 칸을 무시한다.

    nameNormalized·addressNormalized  백엔드 `StoreNormalizer` 를 옮긴 규칙으로 계산
    lat·lng                            인허가 좌표(EPSG:5174, 보정 Bessel 중부원점)를 WGS84 로 변환.
                                       카카오 주소 검색 좌표와 거리 중앙값 14m(다른 후보는 260m 이상).
                                       인허가에 좌표가 없는 행은 비운다(명단의 좌표 채움률은 98.8%)
    category                           인허가 업태구분명
    lastCheckedAt                      비운다 — 담당자가 아직 한 번도 확인하지 않은 가게

    폐업 그룹: 폐업일이 1~18개월 전. 같은 자리에 같은 이름이 다시 영업 중이면 뺀다(주인만 바뀜)
    영업 그룹: 인허가일이 1년 이상 지난 영업/정상 가게

인허가 파일은 file.localdata.go.kr 에서 시군구별로 받는다(인증 없음). 대구 8개 구·군과 군위군을
`eval/fixtures/localdata_daegu_<지역코드>.csv` 로 받아 두었다고 가정한다.

사용법 (좌표 변환에 pyproj 가 필요하다. 이 스크립트에서만 쓰므로 의존성에 넣지 않았다):
    uv run --with pyproj python -m eval.mock_db --n 30     # 그룹당 30곳 → eval/fixtures/mock_nts_checks.csv
"""

from __future__ import annotations

import argparse
import csv
import io
import random
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

from src.backend_client.models import StoreCheck
from src.investigation import ChangeField
from src.investigation.classify import comparison_key, road_address_key

AI_ROOT = Path(__file__).resolve().parent.parent
FIXTURES = AI_ROOT / "eval" / "fixtures"
# fixtures 아래 csv 는 커밋 금지다(.gitignore) — 실제 가게 정보가 들어 있다.
DB_PATH = FIXTURES / "mock_nts_checks.csv"
SEED = 20260927

# 기준일 2026-09-27. 폐업 직후는 웹에 아직 흔적이 없고, 오래되면 흔적이 사라진다.
CLOSED_FROM, CLOSED_TO = "2025-03-27", "2026-08-27"
OPEN_LICENSED_BEFORE = "2025-09-27"

#: 선한영향력가게 명단에서 도로명주소가 비어 있는 비율(경기 883건 중 10%).
JIBUN_ONLY_RATE = 0.10
FAKE_BIZ_NO = "000-00-00000"

#: 인허가 좌표계. 보정 Bessel 중부원점 TM.
LICENSE_CRS = "EPSG:5174"

# `StoreNormalizer.java` 를 옮긴 것. 백엔드 규칙이 바뀌면 같이 바꾼다.
_CORPORATE_MARKS = [
    re.compile(r"\s*".join(word)) for word in ("주식회사", "유한회사", "사단법인", "재단법인")
] + [re.compile(rf"\(\s*{a}\s*\)") for a in ("주", "유", "사", "재")]


def normalize_name(raw: str | None) -> str:
    """`StoreNormalizer.normalizeName` — NFKC → 법인 표기 제거 → 소문자 → 한글·영문·숫자만."""
    if raw is None:
        return ""
    value = unicodedata.normalize("NFKC", raw)
    for mark in _CORPORATE_MARKS:
        value = mark.sub("", value)
    return re.sub(r"[^가-힣a-z0-9]", "", value.lower())


def normalize_address(raw: str | None) -> str:
    """`StoreNormalizer.normalizeAddress` — NFKC → 보이지 않는 문자 제거 → 대시 통일 → 공백 제거."""
    if raw is None:
        return ""
    value = unicodedata.normalize("NFKC", raw)
    value = re.sub("[\u200b-\u200d\ufeff]", "", value)
    value = re.sub("[\u2010-\u2014\u2212]", "-", value)
    return re.sub(r"\s+", "", value)


def _to_wgs84():
    try:
        from pyproj import Transformer
    except ImportError:
        raise SystemExit("좌표 변환에 pyproj 가 필요하다: uv run --with pyproj python -m eval.mock_db") from None
    transformer = Transformer.from_crs(LICENSE_CRS, "EPSG:4326", always_xy=True)

    def convert(r: dict[str, str]) -> tuple[float | None, float | None]:
        x, y = _get(r, "좌표정보(X)"), _get(r, "좌표정보(Y)")
        if not x or not y:
            return None, None
        lng, lat = transformer.transform(float(x), float(y))
        return round(lat, 7), round(lng, 7)

    return convert


COLUMNS = [
    "storeId", "name", "nameNormalized", "addressRoad", "addressNormalized", "lat", "lng",
    "category", "phone", "bizNo", "lastCheckedAt", "internalStatus",
    "ntsLookup", "ntsStatus", "statusComparison", "statusMismatch", "dataProblem",
    "answer_group", "answer_closedAt", "answer_licensedAt", "answer_phone", "answer_addressRoad",
    "answer_licenseId",
]


def _get(row: dict[str, str], key: str) -> str:
    return (row.get(key) or "").strip()


def load_license_rows() -> list[dict[str, str]]:
    paths = sorted(FIXTURES.glob("localdata_daegu_*.csv"))
    if not paths:
        raise SystemExit(f"인허가 파일이 없다: {FIXTURES}/localdata_daegu_*.csv")
    rows = []
    for path in paths:
        # LOCALDATA 원본은 cp949 다.
        rows += csv.DictReader(io.StringIO(path.read_bytes().decode("cp949")))
    return rows


def _same_name(a: str, b: str) -> bool:
    ka, kb = comparison_key(ChangeField.NAME, a), comparison_key(ChangeField.NAME, b)
    return bool(ka and kb) and (ka in kb or kb in ka)


def pick(rows: list[dict[str, str]], n: int) -> list[tuple[str, dict[str, str]]]:
    """폐업·영업 그룹을 n 곳씩 뽑는다."""
    at_address = defaultdict(list)
    for r in rows:
        if key := road_address_key(_get(r, "도로명주소")):
            at_address[(_get(r, "개방자치단체코드"), key)].append(r)

    def reopened(r: dict[str, str]) -> bool:
        here = at_address[(_get(r, "개방자치단체코드"), road_address_key(_get(r, "도로명주소")))]
        return any(
            o is not r and _get(o, "영업상태명") == "영업/정상" and _same_name(_get(o, "사업장명"), _get(r, "사업장명"))
            for o in here
        )

    closed = [
        r
        for r in rows
        if _get(r, "영업상태명") == "폐업"
        and CLOSED_FROM <= _get(r, "폐업일자") <= CLOSED_TO
        and road_address_key(_get(r, "도로명주소"))
        and _get(r, "지번주소")
        and not reopened(r)
    ]
    opened = [
        r
        for r in rows
        if _get(r, "영업상태명") == "영업/정상"
        and _get(r, "인허가일자") <= OPEN_LICENSED_BEFORE
        and road_address_key(_get(r, "도로명주소"))
        and _get(r, "지번주소")
    ]

    # 그룹마다 따로 섞고 앞에서 자른다 — n 을 늘려도 앞서 뽑은 가게가 그대로여서 이어서 돌릴 수 있다.
    def first(group: list[dict[str, str]]) -> list[dict[str, str]]:
        group = sorted(group, key=lambda r: _get(r, "관리번호"))
        random.Random(SEED).shuffle(group)
        return group[:n]

    return [("폐업", r) for r in first(closed)] + [("영업", r) for r in first(opened)]


def to_store_check(store_id: int, group: str, r: dict[str, str], to_wgs84) -> dict[str, object]:
    """인허가 한 행을 선한영향력가게처럼 결함이 있는 `StoreCheck` 행으로 만든다."""
    road = _get(r, "도로명주소")
    # 가게마다 따로 뽑는다 — 표본 크기를 바꿔도 같은 가게는 같은 결함을 갖는다.
    rng = random.Random(f"{SEED}-{_get(r, '관리번호')}")
    address = _get(r, "지번주소") if rng.random() < JIBUN_ONLY_RATE else road
    lat, lng = to_wgs84(r)
    return {
        "storeId": store_id,
        "name": _get(r, "사업장명"),
        "nameNormalized": normalize_name(_get(r, "사업장명")),
        "addressRoad": address,
        "addressNormalized": normalize_address(address),
        "lat": lat,
        "lng": lng,
        "category": _get(r, "업태구분명") or None,
        "phone": None,
        "bizNo": FAKE_BIZ_NO,
        "lastCheckedAt": None,
        "internalStatus": "UNKNOWN",
        "ntsLookup": "CONFIRMED",
        "ntsStatus": "ACTIVE",
        "statusComparison": "NOT_COMPARABLE",
        "statusMismatch": False,
        "dataProblem": False,
        "answer_group": group,
        "answer_closedAt": _get(r, "폐업일자"),
        "answer_licensedAt": _get(r, "인허가일자"),
        "answer_phone": "".join(c for c in _get(r, "전화번호") if c.isdigit()),
        "answer_addressRoad": road,
        "answer_licenseId": _get(r, "관리번호"),
    }


def build(n: int) -> list[dict[str, object]]:
    to_wgs84 = _to_wgs84()
    return [to_store_check(i + 1, group, r, to_wgs84) for i, (group, r) in enumerate(pick(load_license_rows(), n))]


def is_second_round_target(check: StoreCheck) -> bool:
    """백엔드가 2차 조사로 넘길 가게인지 — **백엔드가 할 일을 평가에서 흉내 낸 것이다.**

    운영에서는 백엔드가 1차 조사(국세청 대조)를 마치고 2차 조사 대상만 골라 AI 에 보낸다. AI 는 받은 가게를
    다시 거르지 않는다. 백엔드 `nts-checks` 필터에는 아직 이 조합이 없어서(`STATUS_MISMATCH`·`DATA_PROBLEM`
    뿐) 백엔드에 요청할 규칙을 여기 적어 둔다.

    - 국세청과 상태가 다르면(`statusMismatch`) 이미 변화가 잡혀 1차에서 우선확인이다 — 넘기지 않는다
    - 번호가 없거나 국세청에 없는 번호(`dataProblem`)면 번호부터 찾을 대상이다 — 넘기지 않는다
    - 그 밖에는 넘긴다. 대조 결과가 일치한 가게와 **국세청 조회에 실패한 가게**가 여기 든다
    """
    return not check.status_mismatch and not check.data_problem


def load() -> list[dict[str, object]]:
    """저장해 둔 간이 DB 를 읽는다. CSV 라 빈 칸은 None, 불리언은 되살린다."""
    with DB_PATH.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    for row in rows:
        for key, value in row.items():
            if value == "":
                row[key] = None
            elif value in ("True", "False"):
                row[key] = value == "True"
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="인허가로 간이 DB(nts-checks 행) 만들기")
    parser.add_argument("--n", type=int, default=30, help="그룹당 가게 수")
    args = parser.parse_args()
    rows = build(args.n)
    # 엑셀에서 바로 열리게 BOM 을 붙인다.
    with DB_PATH.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    jibun = sum(r["addressRoad"] != r["answer_addressRoad"] for r in rows)
    print(f"{len(rows)}곳 (지번 주소만 {jibun}곳) → {DB_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
