"""BiznoClient의 응답 파싱 로직 테스트 — 네트워크는 타지 않는다.

비즈노가 실제로 돌려준 형태를 그대로 넣어서, 우리 쪽 파싱이 버티는지만 본다.
관측된 함정은 `src/biz_number/bizno.py` 독스트링에 정리돼 있다.
"""

import json
from unittest.mock import patch

import pytest

from src.biz_number.bizno import PAGE_SIZE, BiznoClient, BiznoError, BiznoRecord, digits_only


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("756-87-02117", "7568702117"),
        ("7568702117", "7568702117"),
        ("756 87 02117", "7568702117"),
        ("", ""),
    ],
)
def test_digits_only_strips_separators(raw, expected):
    assert digits_only(raw) == expected


def _fake_response(payload: dict):
    """urlopen이 컨텍스트 매니저로 쓰이므로 __enter__/__exit__를 흉내낸다."""

    class _Response:
        def read(self):
            return json.dumps(payload).encode()

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    return _Response()


def _client():
    return BiznoClient(api_key="test-key")


def test_search_parses_items():
    payload = {
        "resultCode": 0,
        "resultMsg": "NORMAL SERVICE.",
        "totalCount": 1,
        "items": [
            {
                "company": "주식회사 우리요리",
                "bno": "756-87-02117",
                "cno": "134111-0591839",
                "bsttcd": "01",
                "bstt": "계속사업자",
                "TaxTypeCd": "",
                "taxtype": "부가가치세 일반과세자",
                "EndDt": "",
            }
        ],
    }
    with patch("src.biz_number.bizno.urllib.request.urlopen", return_value=_fake_response(payload)):
        records, complete = _client().search_by_name("주식회사 우리요리")

    assert complete is True  # maxpage가 없으면 1페이지로 본다
    assert records == [
        BiznoRecord(
            company="주식회사 우리요리",
            bizno="756-87-02117",
            corp_no="134111-0591839",
            status="계속사업자",
            status_code="01",
            tax_type="부가가치세 일반과세자",
            closed_date="",
        )
    ]


@pytest.mark.parametrize(
    "items",
    [
        "",  # 0건일 때 빈 리스트가 아니라 빈 문자열로 온다
        [],
        [None],  # 배열 안에 null이 섞여 오는 경우가 있다
    ],
)
def test_empty_or_null_items_yield_no_records(items):
    payload = {"resultCode": 0, "resultMsg": "NORMAL SERVICE.", "totalCount": 0, "items": items}
    with patch("src.biz_number.bizno.urllib.request.urlopen", return_value=_fake_response(payload)):
        assert _client().search_by_name("없는가게") == ([], True)


def test_error_result_code_raises():
    """인증 실패도 HTTP 200으로 내려온다 — 본문의 resultCode를 봐야 한다."""
    payload = {"resultCode": -1, "resultMsg": "미등록 사용자입니다.", "totalCount": 0, "items": ""}
    with patch("src.biz_number.bizno.urllib.request.urlopen", return_value=_fake_response(payload)):
        with pytest.raises(BiznoError, match="미등록 사용자입니다"):
            _client().search_by_name("아무거나")


def test_lookup_by_bizno_returns_none_when_absent():
    payload = {"resultCode": 0, "resultMsg": "NORMAL SERVICE.", "totalCount": 0, "items": []}
    with patch("src.biz_number.bizno.urllib.request.urlopen", return_value=_fake_response(payload)):
        assert _client().lookup_by_bizno("000-00-00000") is None


def test_missing_api_key_raises():
    with patch.dict("os.environ", {"BIZNO_API_KEY": ""}, clear=False):
        with pytest.raises(BiznoError, match="BIZNO_API_KEY"):
            BiznoClient()


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("406-66-00446", True),
        ("4066600446", True),
        ("495-86-0****", False),  # 웹에서 뒷자리가 가려진 채로 온다
        ("", False),
        ("123", False),
    ],
)
def test_is_well_formed(raw, expected):
    from src.biz_number.bizno import is_well_formed

    assert is_well_formed(raw) is expected



# ── 페이지네이션 ──────────────────────────────────────────────
# 파라미터 이름은 `pg`가 아니라 `page`다. 응답 envelope의 maxpage로 전체 쪽수를 알 수 있다.


def _paged_response(pages: dict[int, dict]):
    """요청 URL의 page 값에 따라 다른 응답을 돌려주는 urlopen 대역."""

    def _open(request, *_, **__):
        url = request.full_url if hasattr(request, "full_url") else str(request)
        page = int(url.split("page=")[1].split("&")[0])
        return _fake_response(pages[page])

    return _open


def _page(records, page, maxpage, total):
    return {
        "resultCode": 0,
        "resultMsg": "NORMAL SERVICE.",
        "page": page,
        "maxpage": maxpage,
        "pagecnt": 10,
        "totalCount": total,
        "items": records,
    }


def _item(company, bizno):
    return {"company": company, "bno": bizno, "cno": "", "bsttcd": "01",
            "bstt": "계속사업자", "TaxTypeCd": "", "taxtype": "", "EndDt": ""}


def test_follows_pages_until_maxpage():
    pages = {
        1: _page([_item("가", "1")] * 10, 1, 3, 25),
        2: _page([_item("나", "2")] * 10, 2, 3, 25),
        3: _page([_item("다", "3")] * 5, 3, 3, 25),
    }
    with patch("src.biz_number.bizno.urllib.request.urlopen", new=_paged_response(pages)):
        records, complete = _client().search_by_name("흔한이름", max_pages=5)

    assert len(records) == 25
    assert complete is True


def test_stops_at_max_pages_and_reports_incomplete():
    """상한에 걸려 멈추면 complete=False — '후보에 없음'을 '존재하지 않음'으로 읽으면 안 된다."""
    pages = {
        1: _page([_item("가", "1")] * 10, 1, 9, 85),
        2: _page([_item("나", "2")] * 10, 2, 9, 85),
    }
    with patch("src.biz_number.bizno.urllib.request.urlopen", new=_paged_response(pages)):
        records, complete = _client().search_by_name("아주흔한이름", max_pages=2)

    assert len(records) == 20
    assert complete is False


def test_lookup_by_bizno_ignores_null_padding():
    """gb=1은 1건 + null 9개로 온다 — items 길이가 항상 pagecnt라서."""
    payload = _page([_item("(주)제너시스비비큐", "207-81-43555")] + [None] * 9, 1, 1, 1)
    with patch("src.biz_number.bizno.urllib.request.urlopen", return_value=_fake_response(payload)):
        record = _client().lookup_by_bizno("207-81-43555")

    assert record is not None
    assert record.company == "(주)제너시스비비큐"


def test_요청에_pagecnt를_보낸다():
    """안 보내면 비즈노가 기본 10건만 준다 — 같은 커버리지에 호출이 몇 배로 는다.

    실측(2026-09-23): 50·100은 정상, 150부터 `resultCode: -2`. 상한은 100이다.
    """
    seen = {}

    def _capture(request, timeout=None):
        seen["url"] = request.full_url
        return _fake_response(_page([], 1, 1, 0))

    with patch("src.biz_number.bizno.urllib.request.urlopen", new=_capture):
        _client().search_by_name("가게")

    assert f"pagecnt={PAGE_SIZE}" in seen["url"]
    assert PAGE_SIZE <= 100, "비즈노가 pagecnt 100 초과를 거부한다"
