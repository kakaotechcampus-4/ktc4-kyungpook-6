from src.name_search import MockNameSearchProvider


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
