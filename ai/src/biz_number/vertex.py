"""Vertex AI의 구글 그라운딩 검색으로 이름 후보를 찾는 provider

주의 — **구글 그라운딩 검색과 Structured Output 강제를 동시에 못 쓴다**
("controlled generation is not supported with Search tool" 400). 그래서 스키마를
프롬프트로 지시하고 응답 형식을 보정한다(`_coerce_json`). 형식이 보장되지는 않는다.

"""

import json
import os
import re

from google import genai
from google.genai import types

from src.biz_number.web_search import WebSearchProvider

# 스키마를 못 거는 대신 프롬프트로 출력 형식을 지정한다.
# 과제 내용은 공통 프롬프트와 동일하게 두고 형식 지시만 덧붙여서,
# 프록시 provider와의 비교가 프롬프트 차이 때문에 틀어지지 않게 한다.
def _json_only_instruction(schema: dict) -> str:
    """검색 도구와 함께 쓸 수 없는 구조화 출력 대신, 스키마를 프롬프트로 지시한다."""
    return (
        "\n\n응답은 아래 JSON 스키마를 만족하는 객체 하나만 출력해라. "
        "설명 문장이나 코드펜스를 덧붙이지 마라.\n"
        + json.dumps(schema, ensure_ascii=False)
    )


_CODE_FENCE = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.DOTALL)


class VertexWebSearchProvider(WebSearchProvider):
    """ADC 인증으로 Vertex AI를 직접 호출하는 구현체."""

    def _build_client(self, api_key: str | None) -> genai.Client:
        # api_key는 쓰지 않는다 — ADC(application_default_credentials.json)로 인증한다.
        # 상위 클래스 시그니처를 맞추기 위해 받기만 한다.
        return genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )

    def _call(self, prompt: str, schema: dict) -> str:
        response = self._client.models.generate_content(
            model=self._model,
            contents=prompt + _json_only_instruction(schema),
            config=types.GenerateContentConfig(tools=[{"google_search": {}}]),
        )
        # 안전 필터 등으로 text가 None일 수 있다. 빈 문자열로 넘기면
        # 공통 파싱 로직이 CandidateParseError로 변환해준다.
        return _coerce_json(response.text or "")


def _coerce_json(text: str) -> str:
    """JSON만 오라고 부탁했지만 강제할 수 없으므로, 흔한 이탈을 되돌린다.

    검색 도구를 쓰면 스키마 강제(controlled generation)를 못 걸기 때문에 형식이 보장되지
    않는다. 실제로 관측되는 이탈 두 가지를 여기서 흡수한다.

      1. ```json ... ``` 코드펜스로 감싸 옴 — "덧붙이지 마라"고 해도 잦다
      2. 설명 문장을 앞뒤에 붙여 옴 — "여기 결과입니다: {...} 도움이 되셨길"

    2번은 바깥쪽 중괄호 구간만 잘라낸다. 그래도 JSON이 아니면 그대로 올려보내
    공통 파싱 로직이 CandidateParseError로 바꾸게 둔다 — 여기서 삼키지 않는다.
    """
    text = text.strip()
    match = _CODE_FENCE.match(text)
    if match:
        text = match.group(1).strip()
    if text.startswith("{"):
        return text

    start, end = text.find("{"), text.rfind("}")
    return text[start : end + 1] if 0 <= start < end else text
