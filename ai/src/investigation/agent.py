"""조사 에이전트 — 가게 상태와 중간 결과를 보고 도구를 골라 부른다. 판정은 규칙(`classify.py`)이 한다.

담당자가 하던 조사를 흉내 낸다: 지도에서 가게를 찾아 대조하고, 안 맞거나 못 찾은 항목만 웹에서 더 찾는다.

    도구
      check_maps()             지도 대조(네이버 지역 검색·카카오). LLM 에는 항목별 "일치·다름·못 찾음" 만 알린다
                               — 카카오 값은 외부 LLM 에 넘길 수 없다(약관). 값과 링크는 코드에만 남는다
      web_search(field, goal)  항목·목적을 좁힌 웹검색(그라운딩 호출을 따로 한다 — Gemini 는 그라운딩과
                               함수 도구를 한 요청에 같이 쓸 수 없다)
      finish(reason)           조사를 끝낸다
    상한: 도구 호출 MAX_STEPS 번, 그중 웹검색 MAX_WEB_SEARCHES 번

**판정은 에이전트가 하지 않는다.** 모은 관측을 규칙이 판정한다(멘토 결정 — 모델 확신도로 가르지 않는다).
에이전트가 정하는 건 "무엇을 더 찾을지" 뿐이다. 조사 과정은 `last_trace` 에 남긴다.

프레임워크 없이 함수 호출 루프를 직접 돈다 — 호출 횟수를 세고 단계마다 기록하려고.

**판단 모델과 웹검색 모델은 따로다.** 웹검색은 구글 그라운딩이 필요해 늘 Vertex Gemini 로 부른다. 판단은
기본으로 같은 Gemini 를 쓰고, `AGENT_MODEL` 을 주면 OpenAI 호환 API(카테캠 프록시 등)의 그 모델이 한다 —
프록시는 그라운딩 같은 제공자 실행 도구를 거부하지만 우리가 실행하는 함수 도구는 쓸 수 있다. 판단은
Responses API(`/v1/responses`)로 부른다 — gpt-6-luna 는 chat/completions 에서 함수 도구를 쓰려면 추론을 꺼야 한다.
상태는 서버에 남기지 않고(`store=false`) 암호화된 추론을 다음 턴에 되돌려 준다.

    AGENT_MODEL     판단 모델 이름. 비우면 Vertex Gemini(MODEL)
    AGENT_BASE_URL  OpenAI 호환 /v1 주소. 비우면 GOOGLE_PROXY_URL + "/v1"
    AGENT_API_KEY   그 API 키. 비우면 GOOGLE_API_KEY
"""

from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import dataclass, field as dc_field

import httpx

from src.investigation.classify import classify, relation_to_db
from src.investigation.models import ChangeField, InvestigationTarget, StoreFinding
from src.investigation.web import RATE_LIMIT_WAITS_SECONDS, PlaceChecker, _is_rate_limited
from src.investigation.web_research import (
    LLM_TIMEOUT_SECONDS,
    build_research_prompt,
    NOTHING_FOUND,
    Observation,
    ResearchParseError,
    ResearchResult,
    ResearchTimeout,
    VertexResearchProvider,
)

MODEL = "gemini-2.5-flash"
MAX_STEPS = 4
MAX_WEB_SEARCHES = 2

_FIELD_NAMES = {"name": ChangeField.NAME, "address": ChangeField.ADDRESS, "phone": ChangeField.PHONE,
                "status": ChangeField.STATUS}
_FIELD_LABELS = {ChangeField.NAME: "상호", ChangeField.ADDRESS: "주소", ChangeField.PHONE: "전화",
                 ChangeField.STATUS: "영업 상태"}
_RELATION_LABELS = {"same": "일치", "different": "다름", "inconclusive": "판단 보류", "unknown": "DB 값 없음"}

logger = logging.getLogger(__name__)


def build_agent_prompt(target: InvestigationTarget) -> str:
    return f"""너는 가게 정보를 확인하는 담당자를 돕는 조사원이다. 우리 DB 의 아래 값이 지금도 맞는지 확인해라.

상호: {target.name}
주소: {target.address or "(없음)"}
전화: {target.phone or "(없음)"}

도구:
- check_maps(): 네이버 지도·카카오맵에 등록된 정보와 DB 를 항목별로 대조한다. 빠르고 싸다. 먼저 써라
- web_search(field, goal): 웹에서 찾는다. 느리고 비싸다. field 는 name·address·phone·status 중 하나, 또는 all.
  goal 에는 무엇을 확인하려는지 적어라(예: "지도에 없는 전화번호 확인", "지도와 다른 주소가 이전인지 확인")
- finish(reason): 조사를 끝낸다

방법:
- 지도에서 모든 항목이 "일치" 면 바로 끝내라
- 지도에서 **아무 항목도 못 찾았으면** web_search("all") 한 번으로 전부 찾아라. 항목을 나눠 찾지 마라
- 일부만 "다름"·"못 찾음"·"판단 보류" 면 그 항목만 찾아라. 이미 일치한 항목은 다시 찾지 마라
- 웹검색은 많아야 {MAX_WEB_SEARCHES}번이다. 더 찾을 것이 없으면 끝내라
- 값이 바뀌었는지는 네가 판단하지 않는다. 찾기만 해라"""


def build_continue_prompt(target: InvestigationTarget, maps_summary: str) -> str:
    """지도 대조를 마친 뒤 이어서 조사하는 프롬프트(혼합 방식). 지도로 확정되지 않은 가게만 여기 온다."""
    return f"""너는 가게 정보를 확인하는 담당자를 돕는 조사원이다. 우리 DB 의 아래 값이 지금도 맞는지 확인하고 있다.

상호: {target.name}
주소: {target.address or "(없음)"}
전화: {target.phone or "(없음)"}

지도 대조는 이미 했다. 결과(항목: DB 와의 관계(출처)):
{maps_summary}

지도만으로는 확정되지 않았다. 웹에서 무엇을 더 찾을지 정해라.

도구:
- web_search(field, goal): 웹에서 찾는다. field 는 name·address·phone·status 중 하나, 또는 all.
  goal 에는 무엇을 확인하려는지 적어라
- finish(reason): 조사를 끝낸다

방법:
- 카카오맵에서만 "다름" 인 항목은 그 항목을 웹에서 찾아 실제 값을 확인해라(카카오 값은 수정안에 쓸 수 없다)
- 지도에서 **아무 항목도 못 찾았으면** web_search("all") 한 번으로 전부 찾아라
- "못 찾음"·"판단 보류" 인 항목만 찾아라. 이미 일치한 항목은 다시 찾지 마라
- 웹검색은 많아야 {MAX_WEB_SEARCHES}번이다. 더 찾을 것이 없으면 끝내라
- 값이 바뀌었는지는 네가 판단하지 않는다. 찾기만 해라"""


def build_focused_prompt(target: InvestigationTarget, change_field: ChangeField, goal: str) -> str:
    """한 항목만 찾는 웹검색 프롬프트. 줄 형식·규칙은 기본 조사(`build_research_prompt`)와 같다."""
    item = {ChangeField.NAME: "name: 현재 상호명. 상호를 바꿨다면 바뀐 이름",
            ChangeField.ADDRESS: "address: 현재 도로명 주소. 다른 곳으로 이전했다면 이전한 주소",
            ChangeField.PHONE: "phone: 현재 전화번호",
            ChangeField.STATUS: "status: 영업 상태. 영업 중이면 OPEN, 휴업이면 SUSPENDED, 폐업이면 CLOSED"}[change_field]
    return f"""다음 가게의 **현재** 정보를 웹 검색으로 확인해줘. 확인 목적: {goal}

가게 이름: {target.name}
주소: {target.address or "(모름)"}
전화번호: {target.phone or "(모름)"}

확인할 것 (이 항목만):
- {item}

찾은 것마다 한 줄씩 아래 형식으로만 적어라. 다른 문장은 쓰지 마라.
항목 | 값 | 근거 문장 | 날짜

규칙:
- **이 가게에 대한 검색 결과만** 적어라. 이름이 같은 다른 지점이면 빼라
- 출처마다 따로 적어라. 근거를 댈 수 없으면 적지 마라. 추측하거나 지어내지 마라
- 날짜는 근거에 게시일·리뷰 작성일 등이 있으면 YYYY-MM-DD, 없으면 비워 둬라
- 찾은 것이 없으면 "{NOTHING_FOUND}" 한 단어만 적어라"""


#: OpenAI 호환 Responses API 에 줄 도구 정의. Gemini 쪽은 파이썬 함수의 서명·설명에서 SDK 가 만든다.
OPENAI_TOOLS = [
    {"type": "function", "name": "check_maps", "description": "네이버 지도·카카오맵에 등록된 정보와 DB 를 항목별로 대조한다.",
     "parameters": {"type": "object", "properties": {}}},
    {"type": "function", "name": "web_search",
     "description": "웹에서 찾는다. field 는 name·address·phone·status 중 하나, 또는 all(전부). goal 에는 확인 목적을 적는다.",
     "parameters": {"type": "object", "required": ["field", "goal"], "properties": {
         "field": {"type": "string", "enum": ["name", "address", "phone", "status", "all"]},
         "goal": {"type": "string"}}}},
    {"type": "function", "name": "finish", "description": "조사를 끝낸다. reason 에 끝내는 이유를 적는다.",
     "parameters": {"type": "object", "required": ["reason"], "properties": {"reason": {"type": "string"}}}},
]


class OpenAIDecider:
    """OpenAI 호환 Responses API 로 다음 도구를 고른다(판단 전용 — 웹검색은 하지 않는다)."""

    def __init__(self, model: str, base_url: str, api_key: str, *, http: httpx.Client | None = None) -> None:
        self.model = model
        self._url = base_url.rstrip("/") + "/responses"
        self._http = http if http is not None else httpx.Client(
            timeout=LLM_TIMEOUT_SECONDS, headers={"Authorization": f"Bearer {api_key}"})

    @classmethod
    def from_env(cls) -> OpenAIDecider | None:
        model = os.environ.get("AGENT_MODEL")
        if not model:
            return None
        base = os.environ.get("AGENT_BASE_URL") or (os.environ.get("GOOGLE_PROXY_URL", "").rstrip("/") + "/v1")
        return cls(model, base, os.environ.get("AGENT_API_KEY") or os.environ.get("GOOGLE_API_KEY", ""))

    def respond(self, items: list[dict], tool_names: list[str] | None = None) -> list[dict]:
        """지금까지의 입력·출력 항목을 주고 이번 출력 항목(추론·함수 호출·문장)을 받는다."""
        tools = [t for t in OPENAI_TOOLS if tool_names is None or t["name"] in tool_names]
        response = self._http.post(self._url, json={
            "model": self.model, "input": items, "tools": tools,
            "store": False, "include": ["reasoning.encrypted_content"],
        })
        response.raise_for_status()
        return response.json()["output"]


@dataclass
class Trace:
    steps: list[str] = dc_field(default_factory=list)
    web_searches: int = 0
    llm_calls: int = 0  # 판단(에이전트) 호출 + 웹검색 호출
    queries: list[str] = dc_field(default_factory=list)


class AgentInvestigator:
    def __init__(
        self,
        research: VertexResearchProvider,
        *,
        map_lookup=None,
        place_checker: PlaceChecker | None = None,
        model: str = MODEL,
        client: object | None = None,
        decider: OpenAIDecider | None = None,
        sleep=time.sleep,
    ) -> None:
        self._decider = decider
        self._research = research
        self._map_lookup = map_lookup
        self._place_checker = place_checker
        self._model = model
        self._client = client if client is not None else research._client
        self._sleep = sleep
        self.last_trace = Trace()
        self.last_observations: list[Observation] = []

    # ------------------------------------------------------------ 도구

    def _summarize(self, target: InvestigationTarget, observations: list[Observation], fields) -> str:
        lines = []
        for change_field in fields:
            found = [o for o in observations if o.field is change_field and o.sources]
            if not found:
                lines.append(f"{_FIELD_LABELS[change_field]}: 못 찾음")
                continue
            parts = []
            for o in found:
                relation = _RELATION_LABELS[relation_to_db(target, change_field, o.value)]
                # 저장할 수 없는 값(카카오)은 LLM 에 넘기지 않는다 — 관계와 출처만 알린다.
                shown = f" {o.value}" if o.storable else ""
                parts.append(f"{relation}({sorted(o.domains)[0]}{shown})")
            lines.append(f"{_FIELD_LABELS[change_field]}: " + ", ".join(parts))
        return "\n".join(lines)

    def _generate(self, contents, config):
        waits = iter(RATE_LIMIT_WAITS_SECONDS)
        while True:
            try:
                return self._client.models.generate_content(model=self._model, contents=contents, config=config)
            except Exception as e:
                wait = next(waits, None) if _is_rate_limited(e) else None
                if wait is None:
                    raise
                self.last_trace.steps.append(f"429 — {wait:.0f}초 대기")
                self._sleep(wait)

    def _search(self, prompt: str) -> ResearchResult:
        """웹검색 한 번. 429 면 `web.py` 와 같은 간격으로 기다렸다 다시 부른다."""
        waits = iter(RATE_LIMIT_WAITS_SECONDS)
        while True:
            try:
                return self._research.research_with_prompt(prompt)
            except (ResearchParseError, ResearchTimeout):
                raise
            except Exception as e:
                wait = next(waits, None) if _is_rate_limited(e) else None
                if wait is None:
                    raise
                self.last_trace.steps.append(f"429 — {wait:.0f}초 대기")
                self._sleep(wait)

    # ------------------------------------------------------------ 조사

    def investigate(self, target: InvestigationTarget) -> StoreFinding:
        """처음부터 조사한다 — 지도 대조도 에이전트가 고른다."""
        return self._investigate(target, [], maps_done=False)

    def continue_from(self, target: InvestigationTarget, maps: list[Observation]) -> StoreFinding:
        """지도 대조를 마친 가게를 이어서 조사한다(혼합 방식) — 지도로 확정되지 않은 가게만 온다."""
        return self._investigate(target, list(maps), maps_done=True)

    def _investigate(self, target: InvestigationTarget, start: list[Observation], *, maps_done: bool) -> StoreFinding:
        trace = self.last_trace = Trace()
        observations: list[Observation] = start
        self.last_observations = observations
        checked_maps = maps_done

        def check_maps() -> str:
            """네이버 지도·카카오맵에 등록된 정보와 DB 를 항목별로 대조한다."""
            nonlocal checked_maps
            if checked_maps:
                return "이미 대조했다"
            checked_maps = True
            if self._map_lookup is None:
                trace.steps.append("check_maps → 지도 대조 꺼짐")
                return "지도 대조를 쓸 수 없다. 웹에서 찾아라"
            found = self._map_lookup.observe(target)
            observations.extend(found)
            summary = self._summarize(target, found, (ChangeField.NAME, ChangeField.ADDRESS, ChangeField.PHONE))
            trace.steps.append("check_maps → " + summary.replace("\n", " / "))
            return summary

        def web_search(field: str, goal: str) -> str:
            """웹에서 찾는다. field 는 name·address·phone·status 중 하나, 또는 all(전부). goal 에는 확인 목적을 적는다."""
            change_field = _FIELD_NAMES.get(field)
            if change_field is None and field != "all":
                return "field 는 name·address·phone·status·all 중 하나다"
            if trace.web_searches >= MAX_WEB_SEARCHES:
                return "웹검색 한도를 다 썼다. finish 로 끝내라"
            trace.web_searches += 1
            trace.llm_calls += 1
            prompt = build_research_prompt(target) if change_field is None else build_focused_prompt(target, change_field, goal)
            try:
                result = self._search(prompt)
            except Exception as e:  # noqa: BLE001 - 검색 한 번이 깨져도 조사는 이어 간다(판단 모델이 다음을 정한다)
                trace.steps.append(f"web_search({field}, {goal!r}) → 실패: {type(e).__name__}")
                return "검색이 실패했다"
            fields = (ChangeField.NAME, ChangeField.ADDRESS, ChangeField.PHONE, ChangeField.STATUS) if change_field is None else (change_field,)
            found = [o for o in result.observations if o.field in fields]
            observations.extend(found)
            trace.queries += result.queries
            summary = self._summarize(target, found, fields)
            trace.steps.append(f"web_search({field}, {goal!r}) → {summary}")
            return summary

        def finish(reason: str) -> str:
            """조사를 끝낸다. reason 에 끝내는 이유를 적는다."""
            trace.steps.append(f"finish → {reason}")
            return "끝"

        if maps_done:
            summary = self._summarize(target, observations, (ChangeField.NAME, ChangeField.ADDRESS, ChangeField.PHONE))
            trace.steps.append("지도 대조(규칙) → " + summary.replace("\n", " / "))
            prompt = build_continue_prompt(target, summary)
            tools = {"web_search": web_search, "finish": finish}
        else:
            prompt = build_agent_prompt(target)
            tools = {"check_maps": check_maps, "web_search": web_search, "finish": finish}
        if self._decider is not None:
            self._run_openai(prompt, tools, trace)
        else:
            self._run_gemini(prompt, tools, trace)

        place = self._place_checker.check(target) if self._place_checker else None
        logger.info("조사 과정 (storeId=%s): %s", target.store_id, " | ".join(trace.steps))
        return classify(target, ResearchResult(observations, queries=tuple(trace.queries)), place)

    def _run_openai(self, prompt: str, tools: dict, trace: Trace) -> None:
        items: list[dict] = [{"role": "user", "content": prompt}]
        calls = 0
        while calls < MAX_STEPS:
            trace.llm_calls += 1
            output = self._decider.respond(items, list(tools))
            items += output  # 추론(암호화)·함수 호출을 그대로 되돌려 줘야 다음 턴이 이어진다
            tool_calls = [o for o in output if o.get("type") == "function_call"]
            if not tool_calls:
                return
            finished = False
            for call in tool_calls:
                calls += 1
                name = call["name"]
                fn = tools.get(name)
                try:
                    args = json.loads(call.get("arguments") or "{}")
                    result = fn(**args) if fn else f"모르는 도구: {name}"
                except (TypeError, ValueError) as e:
                    result = f"도구 인자가 잘못됐다: {e}"
                finished |= name == "finish"
                items.append({"type": "function_call_output", "call_id": call["call_id"], "output": result})
            if finished:
                return
        trace.steps.append("상한 도달")

    def _run_gemini(self, prompt: str, tools: dict, trace: Trace) -> None:
        from google.genai import types

        http_options = types.HttpOptions(timeout=int(LLM_TIMEOUT_SECONDS * 1000))
        config = types.GenerateContentConfig(
            tools=list(tools.values()),
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            http_options=http_options,
        )
        contents = [types.Content(role="user", parts=[types.Part(text=prompt)])]
        calls = 0
        while calls < MAX_STEPS:
            trace.llm_calls += 1
            response = self._generate(contents, config)
            contents.append(response.candidates[0].content)
            function_calls = response.function_calls or []
            if not function_calls:
                break
            replies, finished = [], False
            for call in function_calls:
                calls += 1
                fn = tools.get(call.name)
                result = fn(**(call.args or {})) if fn else f"모르는 도구: {call.name}"
                finished |= call.name == "finish"
                replies.append(types.Part.from_function_response(name=call.name, response={"result": result}))
            if finished:
                break
            contents.append(types.Content(role="user", parts=replies))
        else:
            trace.steps.append("상한 도달")
