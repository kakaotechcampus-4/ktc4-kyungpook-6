"""비즈노 조회 실패 시, web_search + LLM으로 정식 등록 상호명을 추출하는 모듈.

예: Store.name="성심당" 으로 비즈노 조회가 실패했을 때,
    "성심당 정식 등록명"을 검색해 실제 등록 상호명("로쏘")을 찾아낸다.

- NameSearchProvider: 어떤 구현체(Mock, 실제 벤더 provider)든 따라야 하는 외부 계약
- WebSearchProvider: web_search류 도구를 쓰는 벤더 provider들의 공통 뼈대 (현재 Claude 구현)
- MockNameSearchProvider: 실제 API 호출 없이 코드 로직만 검증하는 테스트용 스텁
"""

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Protocol

# 여러 벤더 provider(Claude, OpenAI 등)가 공유하는 프롬프트 + 응답 스키마.
# 벤더별로 다르게 물어보거나 다르게 파싱하면 정확도 비교가 공정하지 않으니 한 곳에서 관리한다.


def build_search_prompt(store_name: str, address: str) -> str:
    return f"""다음 가게의 정식 사업자 등록 상호명(법인명/사업자명)을 웹 검색으로 찾아줘.

가게 이름: {store_name}
주소: {address}

정식 등록 상호명을 찾으면 official_name에 그 이름만 넣고, confidence에 확신도(0~1)를 넣어줘.
못 찾았거나 확신할 수 없으면 official_name을 null로, confidence를 0으로 넣어줘."""


# Claude(output_config.format)와 OpenAI(text.format) 둘 다 표준 JSON Schema를 받으므로
# 스키마 정의 자체는 하나만 두고 양쪽에서 그대로 재사용한다.
NAME_SEARCH_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "official_name": {"type": ["string", "null"]},
        "confidence": {"type": "number"},
    },
    "required": ["official_name", "confidence"],
    "additionalProperties": False,
}


@dataclass
class OfficialNameResult:
    official_name: str | None  # 못 찾으면 None
    confidence: float  # 0.0 ~ 1.0


class NameSearchProvider(Protocol):
    """web_search 호출 + LLM 추출을 수행하는 구현체가 따라야 하는 인터페이스."""

    def search_official_name(self, store_name: str, address: str) -> OfficialNameResult: ...


class WebSearchProvider(ABC):
    """web_search 도구를 쓰는 벤더 provider들의 공통 뼈대.

    벤더마다 실제로 다른 부분은 "클라이언트를 어떻게 만드는지"와 "API를 어떻게 호출해서
    JSON 문자열을 뽑는지" 뿐이다. 그 두 가지만 하위 클래스가 채우면, 그 JSON을 파싱해서
    OfficialNameResult로 바꾸는 공통 로직은 여기서 한 번만 구현한다.
    """

    def __init__(self, model: str, api_key: str | None = None):
        self._model = model
        self._client = self._build_client(api_key)

    @abstractmethod
    def _build_client(self, api_key: str | None):
        """벤더 SDK 클라이언트를 생성한다. api_key가 None이면 SDK 기본 환경변수를 쓴다."""

    @abstractmethod
    def _call(self, prompt: str) -> str:
        """prompt를 벤더 API에 보내고, NAME_SEARCH_JSON_SCHEMA 형식의 JSON 문자열을 반환한다."""

    def search_official_name(self, store_name: str, address: str) -> OfficialNameResult:
        raw = self._call(build_search_prompt(store_name, address))
        data = json.loads(raw)
        return OfficialNameResult(
            official_name=data["official_name"],
            confidence=float(data["confidence"]),
        )


class MockNameSearchProvider:
    """실제 구현 전, 알려진 케이스로만 응답하는 스텁."""

    _KNOWN_CASES = {
        "성심당": "로쏘",
    }

    def search_official_name(self, store_name: str, _address: str) -> OfficialNameResult:
        official_name = self._KNOWN_CASES.get(store_name)
        return OfficialNameResult(
            official_name=official_name,
            confidence=0.9 if official_name else 0.0,
        )
