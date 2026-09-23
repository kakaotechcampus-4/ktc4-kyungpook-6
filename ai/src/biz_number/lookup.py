"""사업자 조회의 공용 어휘와 계약 — 누가 조회하는지와 무관한 것들.

구현체는 지금 `bizno.BiznoClient`(직접 호출) 하나지만 백엔드 경유로 갈아끼울 예정이다
(`docs/백엔드_연동_방식.md`). **와이어 포맷 지식은 전부 구현체가 갖는다** — 필드명,
널 패딩, 페이지 파라미터. 파이프라인은 `BiznoRecord`만 본다.
"""

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class BiznoRecord:
    """사업자 한 건.

    **주소·업종이 없다** — 비즈노가 주지 않는다. 동명 업체를 가릴 수 없는 원인이라
    매칭의 핵심 제약이다 (`matching.py`).
    """

    company: str  # 국세청 등록상호명
    bizno: str  # 사업자등록번호 (하이픈 포함)
    corp_no: str  # 법인등록번호, 개인사업자는 빈 문자열
    status: str  # "계속사업자" / "폐업자" 등
    status_code: str  # bsttcd. "01" = 계속사업자
    tax_type: str
    closed_date: str  # 폐업일. 폐업이 아니면 빈 문자열


# 국세청이 계속사업자에 쓰는 코드. 이 값이 아니면 폐업·휴업 등으로 본다.
ACTIVE_STATUS_CODE = "01"


class BiznoError(Exception):
    """사업자 조회가 실패했거나 응답이 기대한 형태가 아닐 때."""


def digits_only(bizno: str) -> str:
    """사업자등록번호에서 숫자만 남긴다 — 하이픈 유무가 제각각이라 비교 전에 맞춘다."""
    return "".join(c for c in bizno if c.isdigit())


def is_well_formed(bizno: str) -> bool:
    """조회해볼 가치가 있는 번호인가 (10자리).

    웹 페이지가 뒷자리를 가려놓으면(`495-86-0****`) 모델이 그대로 물어온다.
    체크섬은 보지 않는다 — 조회해보고 없으면 없는 대로 처리한다.
    """
    return len(digits_only(bizno)) == 10


class BiznoLookup(Protocol):
    """사업자 조회 구현체가 따라야 하는 계약.

    `search_by_name`의 두 번째 값이 핵심이다. "후보에 없다"(후보가 틀렸으니 재생성)와
    "더 있는데 못 봤다"(더 봐야 함)는 다음 행동이 다르므로 버리면 안 된다.
    """

    def lookup_by_bizno(self, bizno: str) -> BiznoRecord | None:
        """번호로 조회한다. 없는 번호면 None."""
        ...

    def search_by_name(self, name: str) -> tuple[list[BiznoRecord], bool]:
        """상호명으로 검색한다. `(레코드, 끝까지 봤는가)`. 고르는 건 호출하는 쪽 몫이다."""
        ...
