"""카테캠 프록시를 통해 Gemini를 호출하는 provider.

카테캠이 발급하는 Gemini 키는 구글 API를 직접 호출하지 않는다 — mlapi.run 프록시를
거쳐야 하고, 그 프록시는 OpenAI Chat Completions 형식을 그대로 따른다. 그래서
google-genai SDK가 아니라 openai SDK를 프록시 base_url로 가리켜서 쓴다.

주의: 이 프록시는 "provider-executed tools"(서버사이드 웹서치 도구)를 지원하지 않는다 —
"Only client-executed function tools are supported"로 거부됨. 그래서 Claude/OpenAI
버전과 달리 지금은 실제 웹 검색 없이 모델이 원래 아는 지식만으로 답한다. 실제 검색이
필요하면 client-executed function tool(직접 검색 API 호출 + 결과를 다시 모델에 전달하는
루프)을 별도로 구현해야 한다 — 지금은 그 작업 전 단계.
"""

import os

import openai

from src.name_search import NAME_SEARCH_JSON_SCHEMA, WebSearchProvider


class GoogleWebSearchProvider(WebSearchProvider):
    """카테캠 프록시(OpenAI 호환) 경유 Gemini 구현체."""

    def _build_client(self, api_key: str | None) -> openai.OpenAI:
        base_url = os.environ["GOOGLE_PROXY_URL"].rstrip("/") + "/v1"
        return openai.OpenAI(api_key=api_key or os.environ["GOOGLE_API_KEY"], base_url=base_url)

    def _call(self, prompt: str) -> str:
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[{"role": "user", "content": prompt}],
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": "name_search_result",
                    "strict": True,
                    "schema": NAME_SEARCH_JSON_SCHEMA,
                },
            },
        )
        return response.choices[0].message.content
