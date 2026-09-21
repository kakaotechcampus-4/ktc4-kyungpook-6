"""웹검색으로 **이름 후보**를 모으는 계약 — 프롬프트·응답 스키마·provider 뼈대.

`Store.bizNo`가 비어 있을 때 사업자등록번호를 얻는 게 목적이다. 등록상호명은 목적이 아니라
비즈노 조회를 뚫기 위한 수단이다(간판 `성심당`으로는 안 나오고 등록명 `로쏘`로 물어야 나옴).

**모델에게 정답 하나를 요구하지 않는다.** 근거와 함께 후보를 여러 개 받고, 맞는지는
비즈노가 판정한다(`matching.py`). 모델은 정확할 필요 없이 후보 안에 정답이 하나 있으면 된다.

"""
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Protocol

# 여러 벤더 provider가 공유하는 프롬프트 + 응답 스키마.
# 벤더별로 다르게 물어보거나 다르게 파싱하면 비교가 공정하지 않으니 한 곳에서 관리한다.


# 프롬프트를 고치면 올린다 — eval 캐시가 낡은 응답과 섞이지 않게 하는 용도.
#   v1: 이름 + 근거·출처만
#   v2: address, biz_no 추가 (근거에 들어 있으면 주워오게)
CANDIDATES_PROMPT_VERSION = "v2"


def build_candidates_prompt(store_name: str, address: str) -> str:
    """"이 가게와 관련된 이름 후보를 근거와 함께 전부 내놔라"고 묻는다.

    하나를 맞히라고 하지 않는 이유는 모델이 못 찾았을 때 기권하지 않고 지어내기 때문이다.
    후보를 여러 개 받아 비즈노로 거르는 쪽이, 하나를 정확히 맞히라고 요구하는 것보다
    실제로 정답에 닿을 확률이 높다.
    """
    return f"""다음 가게의 사업자등록 상호명을 찾기 위한 **이름 후보**를 웹 검색으로 모아줘.

가게 이름: {store_name}
주소: {address}

찾아볼 것 — 이 가게와 관련된 이름이면 무엇이든 후보가 된다.
- 운영 법인명 (예: 간판이 `성심당`이면 법인은 `로쏘`)
- 프랜차이즈 본사·가맹 운영사 이름
- 이 자리에 있었던 이전 상호
- 지점명까지 포함한 정식 명칭

규칙:
- **검색 결과에 근거가 있는 것만** 넣어라. 추측하거나 지어내지 마라
- 후보마다 근거가 된 문장(evidence)과 출처 URL(source_url)을 반드시 함께 적어라
- 근거를 댈 수 없으면 그 후보는 빼라. 후보가 하나도 없으면 빈 배열을 반환해라
- 확신도는 묻지 않는다. 맞는지는 다른 데서 검증한다

근거에 아래 정보가 함께 나오면 그것도 담아라. 없으면 빈 문자열로 둬라 — 지어내지 마라.
- address: 그 후보의 주소. **위에 준 주소와 다른 지점이 검색되는 일이 흔하므로 중요하다**
- biz_no: 사업자등록번호. 사업자 정보 표기에 함께 나오는 경우가 있다"""


# Claude(output_config.format)와 OpenAI(text.format) 둘 다 표준 JSON Schema를 받으므로
# 스키마 정의 자체는 하나만 두고 양쪽에서 그대로 재사용한다.
NAME_CANDIDATES_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "candidates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "evidence": {"type": "string"},
                    "source_url": {"type": "string"},
                    # 근거에서 주워올 수 있으면 담는 부가 정보. 모르면 빈 문자열.
                    # strict 스키마라 required에서 뺄 수 없어 "빈 문자열 허용"으로 둔다.
                    "address": {"type": "string"},
                    "biz_no": {"type": "string"},
                },
                "required": ["name", "evidence", "source_url", "address", "biz_no"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["candidates"],
    "additionalProperties": False,
}

@dataclass(frozen=True)
class NameCandidate:
    """모델이 제시한 이름 후보 하나. **확신도는 담지 않는다.**"""

    name: str
    evidence: str  # 근거가 된 검색 결과 문장
    source_url: str  # 그 문장의 출처
    address: str = ""  # 근거에 주소가 있으면. 다른 지점을 걸러내는 데 쓴다
    biz_no: str = ""  # 근거에 사업자등록번호가 있으면. 있으면 조회를 건너뛸 수 있다

    @property
    def has_evidence(self) -> bool:
        return bool(self.evidence.strip() and self.source_url.strip())


class CandidateParseError(Exception):
    """벤더 API 응답을 NameCandidate 목록으로 바꾸는 과정에서 실패했을 때 발생한다.

    원인은 다양하다 (빈 응답, 스키마를 어긴 JSON, 필드 누락/타입 불일치 등) —
    호출하는 쪽은 원인을 세분화할 필요 없이 "이 건은 실패로 처리"만 하면 되므로 하나로 묶는다.
    """


class CandidateProvider(Protocol):
    """web_search 호출 + LLM 추출을 수행하는 구현체가 따라야 하는 인터페이스."""

    def search_name_candidates(self, store_name: str, address: str) -> list[NameCandidate]: ...


class WebSearchProvider(ABC):
    """web_search 도구를 쓰는 벤더 provider들의 공통 뼈대.

    벤더마다 실제로 다른 부분은 "클라이언트를 어떻게 만드는지"와 "API를 어떻게 호출해서
    JSON 문자열을 뽑는지" 뿐이다. 그 두 가지만 하위 클래스가 채우면, 그 JSON을 파싱하는
    공통 로직은 여기서 한 번만 구현한다.

    `_call`이 스키마를 함께 받는 이유 — 벤더마다 JSON 형식을 강제하는 수단이 다르다.
    구조화 출력(response_format)을 지원하는 쪽은 스키마를 그대로 넘기면 되고,
    검색 도구와 스키마를 동시에 못 쓰는 쪽(Vertex)은 스키마를 프롬프트에 적어 넣는다.
    """

    def __init__(self, model: str, api_key: str | None = None):
        self._model = model
        self._client = self._build_client(api_key)

    @abstractmethod
    def _build_client(self, api_key: str | None):
        """벤더 SDK 클라이언트를 생성한다. api_key가 None이면 SDK 기본 환경변수를 쓴다."""

    @abstractmethod
    def _call(self, prompt: str, schema: dict) -> str:
        """prompt를 벤더 API에 보내고 schema 형식의 JSON 문자열을 반환한다."""

    def search_name_candidates(self, store_name: str, address: str) -> list[NameCandidate]:
        """이름 후보를 근거와 함께 받아온다. **근거 없는 후보는 버린다.**

        버리는 게 핵심이다 — 실측에서 모델이 지어낸 5건이 전부 출처 없이 이름만 내놓은
        것이었다. 근거를 요구하면 그 경로가 닫힌다.
        """
        raw = self._call(build_candidates_prompt(store_name, address), NAME_CANDIDATES_JSON_SCHEMA)
        try:
            items = json.loads(raw)["candidates"]
            candidates = [
                NameCandidate(
                    name=item["name"].strip(),
                    evidence=item["evidence"],
                    source_url=item["source_url"],
                    # 부가 정보는 없을 수 있다 — 스키마를 못 거는 벤더도 있어 get으로 읽는다.
                    address=(item.get("address") or "").strip(),
                    biz_no=(item.get("biz_no") or "").strip(),
                )
                for item in items
            ]
        except (ValueError, KeyError, TypeError, AttributeError) as e:
            raise CandidateParseError(f"{self._model} 후보 응답 파싱 실패: {raw!r}") from e

        return [c for c in candidates if c.name and c.has_evidence]


class MockCandidateProvider:
    """실제 구현 전, 알려진 케이스로만 응답하는 스텁."""

    _KNOWN_CASES = {
        "성심당": "로쏘",
    }

    def search_name_candidates(self, store_name: str, _address: str) -> list[NameCandidate]:
        registered_name = self._KNOWN_CASES.get(store_name)
        if not registered_name:
            return []
        return [
            NameCandidate(
                name=registered_name,
                evidence=f"{store_name}의 운영 법인은 {registered_name}이다.",
                source_url="https://example.test/mock",
            )
        ]
