"""비즈노 조회 — 사업자등록번호 ↔ 상호명.

**테스트용 임시** 백엔드에서 구현한 쪽으로 갈아 끼울 예정

공식 문서가 JS 렌더링이라 스펙을 전부 실제 호출로 알아냈다. 모르면 조용히 틀리는 것들 —
**오류가 HTTP 200으로 온다**(`resultCode != 0`을 직접 검사할 것), **`items`가 0건일 때
빈 문자열이고 배열에 `null`이 섞인다**, **페이지 파라미터는 `page`**(`pg`는 무시된다),
**상태 판정은 `bstt`가 아니라 `bsttcd`로** (빈 값으로 오는 경우가 있다).
아래 코드에 각각 주석으로 달아 뒀다.

**응답에 주소도 업종도 없다.** 상호명 검색이 동명 업체를 여러 건 돌려줄 때
어느 것이 우리 가게인지 이 API만으로는 가릴 수 없다.

**전체 결과가 300건에서 잘린다** — `maxpage × pagecnt`가 항상 300이다(실측 30×10,
6×50, 3×100). 흔한 이름은 끝까지 넘겨도 전부 볼 수 없다.

**응답에 잔여 쿼터가 없다.** 1일 200건을 호출 수로 세는지 응답 건수로 세는지 API로는
확인 못 한다(bizno.net 마이페이지 필요). 호출 수 기준으로 보고 `pagecnt`를 올려 뒀다.
"""

import json
import os
import urllib.error
import urllib.parse
import urllib.request

from dataclasses import dataclass


@dataclass(frozen=True)
class BiznoRecord:
    """사업자 한 건. 주소·업종은 없다 — 비즈노가 주지 않는다(gb 값과 무관)."""

    company: str  # 국세청 등록상호명
    bizno: str  # 사업자등록번호 (하이픈 포함)
    corp_no: str  # 법인등록번호, 개인사업자는 빈 문자열
    status: str  # "계속사업자" / "폐업자" 등
    status_code: str  # bsttcd. "01" = 계속사업자
    tax_type: str
    closed_date: str  # 폐업일. 폐업이 아니면 빈 문자열


def digits_only(bizno: str) -> str:
    """사업자등록번호에서 숫자만 남긴다 — 하이픈 유무가 제각각이라 비교 전에 맞춘다."""
    return "".join(c for c in bizno if c.isdigit())


def is_well_formed(bizno: str) -> bool:
    """조회해볼 가치가 있는 번호인가 (10자리).

    웹 페이지가 뒷자리를 가려놓는 경우가 있어(`495-86-0****`) 모델이 그대로 물어온다.
    체크섬까지 보지는 않는다 — 형식이 맞으면 조회해보고 없으면 없는 대로 처리한다.
    """
    return len(digits_only(bizno)) == 10


# 국세청이 계속사업자에 쓰는 코드. 이 값이 아니면 폐업·휴업 등으로 본다.
ACTIVE_STATUS_CODE = "01"


BASE_URL = "https://bizno.net/api/fapi"

# gb 파라미터. 문서가 아니라 실측으로 확인한 값이다 (2026-09-15).
# 무엇의 약자인지는 확인하지 못했다. 0·4·5·6은 아무것도 돌려주지 않았다.
_GB_BY_BIZNO = "1"  # 사업자등록번호 → 1건
_GB_BY_CORP_NO = "2"  # 법인등록번호 → 그 법인의 사업장들
_GB_BY_NAME = "3"  # 상호명 → 이름이 겹치는 사업자들

# 한 페이지에 몇 건을 받을지(`pagecnt`). items 배열은 항상 이 길이로 오고 모자라는
# 자리는 null로 채워진다 — gb=1은 1건 + null 나머지로 온다.
# 실측(2026-09-23): 100까지 정상, 150부터 `resultCode: -2`. 값은 백엔드
# (`application.yml`)와 맞춘다 — 갈아끼울 때 결과가 달라지면 안 된다.
PAGE_SIZE = 50

# 페이지를 끝까지 넘기지 않는 기본 상한. 무료 티어가 1일 200건이라 흔한 이름
# (예: `주식회사 궁`) 하나에 수십 회를 쓰면 금방 소진된다.
# `pagecnt=50` × 1회 = 50건으로, 예전(10 × 3회 = 30건)보다 호출은 1/3이고 넓다.
# 더 넓히려면 이 값을 올린다 — 호출 수가 그대로 늘어난다.
DEFAULT_MAX_PAGES = 1


@dataclass(frozen=True)
class BiznoPage:
    """응답 한 페이지. envelope의 page/maxpage/totalCount를 그대로 담는다."""

    records: list[BiznoRecord]
    page: int
    max_page: int
    total_count: int


class BiznoError(Exception):
    """비즈노 호출이 실패했거나 응답이 기대한 형태가 아닐 때."""


class BiznoClient:
    def __init__(self, api_key: str | None = None, timeout: int = 20):
        key = api_key or os.environ.get("BIZNO_API_KEY")
        if not key:
            raise BiznoError("BIZNO_API_KEY가 없다 — ai/.env를 확인할 것")
        self._key = key
        self._timeout = timeout

    def _get_page(self, gb: str, q: str, page: int = 1) -> "BiznoPage":
        url = f"{BASE_URL}?" + urllib.parse.urlencode(
            {
                "key": self._key,
                "type": "json",
                "gb": gb,
                "q": q,
                "page": str(page),
                "pagecnt": str(PAGE_SIZE),
            }
        )
        request = urllib.request.Request(url, headers={"User-Agent": "store-info-agent"})
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                payload = json.loads(response.read().decode("utf-8", "replace"))
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            raise BiznoError(f"비즈노 호출 실패 (gb={gb}, q={q!r}, page={page}): {e}") from e

        # resultCode 0이 정상. -1은 인증 실패("미등록 사용자입니다") 등 애플리케이션 레벨 오류로,
        # HTTP는 200으로 내려오므로 여기서 직접 봐야 한다.
        if payload.get("resultCode") != 0:
            raise BiznoError(f"비즈노 오류 응답: {payload.get('resultMsg')!r} (q={q!r})")

        # 0건일 때 items가 빈 리스트가 아니라 빈 문자열로 오는 경우가 있다.
        # 그리고 배열 길이가 항상 PAGE_SIZE라 남는 자리가 null이므로 dict만 골라낸다.
        items = payload.get("items") or []
        return BiznoPage(
            records=[_to_record(item) for item in items if isinstance(item, dict)],
            page=int(payload.get("page") or page),
            max_page=int(payload.get("maxpage") or 1),
            total_count=int(payload.get("totalCount") or 0),
        )

    def lookup_by_bizno(self, bizno: str) -> BiznoRecord | None:
        """번호로 조회한다. 없는 번호면 None."""
        records = self._get_page(_GB_BY_BIZNO, digits_only(bizno)).records
        return records[0] if records else None

    def _search_all(self, gb: str, q: str, max_pages: int) -> tuple[list[BiznoRecord], bool]:
        """페이지를 넘겨가며 모은다. `(레코드, 끝까지 봤는가)`를 돌려준다.

        상한에 걸려 중간에 멈추면 두 번째 값이 False다 — 호출하는 쪽이
        "후보에 없다"를 "존재하지 않는다"로 읽지 않도록 구분해서 알려준다.
        """
        first = self._get_page(gb, q, 1)
        records = list(first.records)
        last_page = min(first.max_page, max_pages)
        for page in range(2, last_page + 1):
            records.extend(self._get_page(gb, q, page).records)
        return records, first.max_page <= max_pages

    def search_by_name(
        self, name: str, max_pages: int = DEFAULT_MAX_PAGES
    ) -> tuple[list[BiznoRecord], bool]:
        """상호명으로 검색한다. 고르는 건 호출하는 쪽 몫이다.

        `(레코드, 끝까지 봤는가)`를 돌려준다. 흔한 이름은 수십 건이 걸리므로
        `max_pages`로 끊는다 — 그때 두 번째 값이 False가 된다.
        """
        return self._search_all(_GB_BY_NAME, name, max_pages)

    def search_by_corp_no(
        self, corp_no: str, max_pages: int = DEFAULT_MAX_PAGES
    ) -> tuple[list[BiznoRecord], bool]:
        """법인등록번호로 그 법인의 사업장들을 찾는다 (직영점이 법인번호를 공유한다)."""
        return self._search_all(_GB_BY_CORP_NO, digits_only(corp_no), max_pages)


def _to_record(item: dict) -> BiznoRecord:
    """비즈노 응답 한 건을 BiznoRecord로. 와이어 포맷을 아는 건 이 파일뿐이다."""
    return BiznoRecord(
        company=item.get("company", ""),
        bizno=item.get("bno", ""),
        corp_no=item.get("cno", ""),
        status=item.get("bstt", ""),
        status_code=item.get("bsttcd", ""),
        tax_type=item.get("taxtype", ""),
        closed_date=item.get("EndDt", ""),
    )
