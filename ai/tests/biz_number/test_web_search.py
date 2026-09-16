import pytest

from src.biz_number.web_search import MockCandidateProvider, CandidateParseError, WebSearchProvider


class _FakeWebSearchProvider(WebSearchProvider):
    """_call이 반환할 raw 문자열(또는 None)을 고정해두는 테스트용 구현체."""

    def __init__(self, raw: str | None):
        self._raw = raw
        super().__init__(model="fake-model")

    def _build_client(self, api_key: str | None):
        return None

    def _call(self, prompt: str, schema: dict) -> str:
        return self._raw


@pytest.mark.parametrize(
    "raw",
    [
        None,
        "이건 JSON이 아님",
        "{}",  # candidates 키 누락
        '{"candidates": "배열이 아님"}',
        '{"candidates": [{"name": "로쏘"}]}',  # evidence/source_url 누락
    ],
)
def test_search_name_candidates_raises_on_malformed_response(raw):
    provider = _FakeWebSearchProvider(raw)

    with pytest.raises(CandidateParseError):
        provider.search_name_candidates("성심당", "대전 중구 은행동")


def test_candidates_without_evidence_are_dropped():
    """근거 없는 후보는 버린다 — 실측에서 지어낸 5건이 전부 출처 없이 이름만 냈다."""
    raw = """{"candidates": [
        {"name": "로쏘", "evidence": "성심당 운영사는 로쏘(주)다.", "source_url": "https://ex.test/1"},
        {"name": "지어낸이름", "evidence": "", "source_url": ""},
        {"name": "출처만없음", "evidence": "어디서 봤는데", "source_url": "  "},
        {"name": "  ", "evidence": "근거", "source_url": "https://ex.test/2"}
    ]}"""

    candidates = _FakeWebSearchProvider(raw).search_name_candidates("성심당", "대전")

    assert [c.name for c in candidates] == ["로쏘"]


def test_mock_returns_candidate_with_evidence():
    candidates = MockCandidateProvider().search_name_candidates("성심당", "대전")

    assert [c.name for c in candidates] == ["로쏘"]
    assert candidates[0].has_evidence


def test_mock_returns_empty_for_unknown_store():
    assert MockCandidateProvider().search_name_candidates("존재하지않는가게", "어딘가") == []


# ── Vertex 응답 형식 보정 ─────────────────────────────────────
# 검색 도구를 쓰면 스키마를 강제할 수 없어 형식 이탈이 구조적으로 가능하다.

from src.biz_number.vertex import _coerce_json


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ('{"candidates": []}', '{"candidates": []}'),
        ('```json\n{"candidates": []}\n```', '{"candidates": []}'),
        ('```\n{"candidates": []}\n```', '{"candidates": []}'),
        ('  {"candidates": []}  ', '{"candidates": []}'),
        # 설명 문장을 앞뒤에 붙여 오는 경우
        ('결과입니다: {"candidates": []} 도움이 되셨길', '{"candidates": []}'),
        # JSON이 아예 없으면 그대로 올려보내 CandidateParseError가 나게 둔다
        ("찾을 수 없습니다", "찾을 수 없습니다"),
        ("", ""),
    ],
)
def test_coerce_json(raw, expected):
    assert _coerce_json(raw) == expected
