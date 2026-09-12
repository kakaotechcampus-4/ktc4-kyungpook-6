import pytest

from src.name_search import MockNameSearchProvider, NameSearchError, WebSearchProvider


def test_known_case_resolves_official_name():
    provider = MockNameSearchProvider()

    result = provider.search_official_name("성심당", "대전 중구 은행동")

    assert result.official_name == "로쏘"
    assert result.confidence > 0


def test_unknown_case_returns_none():
    provider = MockNameSearchProvider()

    result = provider.search_official_name("존재하지않는가게", "어딘가")

    assert result.official_name is None
    assert result.confidence == 0.0


class _FakeWebSearchProvider(WebSearchProvider):
    """_call이 반환할 raw 문자열(또는 None)을 고정해두는 테스트용 구현체."""

    def __init__(self, raw: str | None):
        self._raw = raw
        super().__init__(model="fake-model")

    def _build_client(self, api_key: str | None):
        return None

    def _call(self, prompt: str) -> str:
        return self._raw


@pytest.mark.parametrize(
    "raw",
    [
        None,  # message.content가 None인 경우 (예: 콘텐츠 필터링)
        "이건 JSON이 아님",  # json.JSONDecodeError
        "{}",  # 필수 필드 누락 -> KeyError
        '{"official_name": "로쏘", "confidence": "높음"}',  # confidence가 숫자가 아님 -> ValueError
    ],
)
def test_search_official_name_raises_on_malformed_response(raw):
    provider = _FakeWebSearchProvider(raw)

    with pytest.raises(NameSearchError):
        provider.search_official_name("성심당", "대전 중구 은행동")
