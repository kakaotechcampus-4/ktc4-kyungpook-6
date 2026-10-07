"""조사 에이전트 — 판단 모델이 고른 도구를 실행하고, 판정은 규칙이 한다."""

from __future__ import annotations

import json

from src.backend_client.models import StoreStatus
from src.investigation.agent import MAX_WEB_SEARCHES, AgentInvestigator
from src.investigation.models import ChangeField, InvestigationTarget
from src.investigation.web_research import Observation, ResearchResult, Source

TARGET = InvestigationTarget(
    store_id=1, name="빠레뜨치킨", address="대구광역시 서구 국채보상로67길 38", phone="053-567-3050",
    internal_status=StoreStatus.OPEN,
)


def call(name: str, **args) -> dict:
    return {"type": "function_call", "call_id": f"c-{name}", "name": name, "arguments": json.dumps(args)}


class ScriptedDecider:
    """정해 둔 순서로 도구를 고른다. 받은 입력을 기록한다(LLM 에 무엇이 넘어갔는지 보려고)."""

    def __init__(self, *turns: list[dict]) -> None:
        self.turns, self.seen = list(turns), []

    def respond(self, items: list[dict], tool_names: list[str] | None = None) -> list[dict]:
        self.tool_names = tool_names
        self.seen.append(json.dumps(items, ensure_ascii=False))
        calls = self.turns.pop(0) if self.turns else []
        return calls or [{"type": "message", "content": [{"type": "output_text", "text": "끝"}]}]


class Maps:
    def __init__(self, observations):
        self.observations = observations

    def observe(self, target):
        return self.observations


class Research:
    def __init__(self, result: ResearchResult):
        self.result, self.prompts = result, []

    def research_with_prompt(self, prompt):
        self.prompts.append(prompt)
        return self.result


def kakao(field: ChangeField, value: str) -> Observation:
    return Observation(field=field, value=value, evidence="카카오맵에 등록된 값", observed_at="2026-10-06",
                       sources=(Source("map.kakao.com", "http://place.map.kakao.com/1"),), storable=False)


def web(field: ChangeField, value: str) -> Observation:
    return Observation(field=field, value=value, evidence=f"값 {value}", sources=(Source("blog.naver.com", "https://b"),))


def test_지도에서_다른_전화를_보고_웹으로_확인하면_값까지_수정안에_담는다():
    decider = ScriptedDecider([call("check_maps")], [call("web_search", field="phone", goal="지도와 다른 전화 확인")],
                              [call("finish", reason="확인함")])
    maps = Maps([kakao(ChangeField.NAME, "빠레뜨치킨"), kakao(ChangeField.PHONE, "053-567-3080")])
    research = Research(ResearchResult([web(ChangeField.PHONE, "053-567-3080")]))

    agent = AgentInvestigator(research, map_lookup=maps, decider=decider, client=object())
    found = agent.investigate(TARGET)

    assert found.proposed_changes == {"phone": "053-567-3080"}
    assert [s.split(" →")[0] for s in agent.last_trace.steps][:2] == ["check_maps", "web_search(phone, '지도와 다른 전화 확인')"]
    assert "전화" in research.prompts[0] and "이 항목만" in research.prompts[0]


def test_카카오_값은_판단_모델에_넘기지_않는다():
    decider = ScriptedDecider([call("check_maps")], [call("finish", reason="끝")])
    agent = AgentInvestigator(Research(ResearchResult()), map_lookup=Maps([kakao(ChangeField.PHONE, "053-567-3080")]),
                              decider=decider, client=object())

    agent.investigate(TARGET)

    assert "053-567-3080" not in decider.seen[-1]
    assert "다름(map.kakao.com)" in decider.seen[-1]


def test_웹검색은_한도를_넘지_않는다():
    searches = [[call("web_search", field="all", goal=f"{i}번째")] for i in range(MAX_WEB_SEARCHES + 1)]
    research = Research(ResearchResult())
    agent = AgentInvestigator(research, decider=ScriptedDecider(*searches), client=object())

    agent.investigate(TARGET)

    assert len(research.prompts) == MAX_WEB_SEARCHES


def test_판정은_규칙이_한다_도구를_안_불러도_결과가_나온다():
    agent = AgentInvestigator(Research(ResearchResult()), decider=ScriptedDecider(), client=object())

    found = agent.investigate(TARGET)

    assert found.classification.value == "NO_CHANGE" and found.signals == []


class TestContinueFrom:
    """혼합 방식 — 지도 대조를 마친 가게를 에이전트가 이어서 조사한다."""

    def test_지도_결과를_받아_check_maps_없이_시작한다(self):
        decider = ScriptedDecider([call("web_search", field="name", goal="카카오에만 다른 상호 확인")],
                                  [call("finish", reason="확인")])
        research = Research(ResearchResult([web(ChangeField.NAME, "빠레뜨치킨 본점")]))
        agent = AgentInvestigator(research, decider=decider, client=object())

        agent.continue_from(TARGET, [kakao(ChangeField.NAME, "빠레또치킨")])

        assert decider.tool_names == ["web_search", "finish"]
        first_prompt = decider.seen[0]
        assert "지도 대조는 이미 했다" in first_prompt and "빠레또치킨" not in first_prompt
        assert agent.last_trace.steps[0].startswith("지도 대조(규칙)")

    def test_웹에서_확인한_값으로_수정안을_채운다(self):
        decider = ScriptedDecider([call("web_search", field="phone", goal="카카오에만 다른 전화 확인")],
                                  [call("finish", reason="확인")])
        research = Research(ResearchResult([web(ChangeField.PHONE, "053-567-3080")]))
        agent = AgentInvestigator(research, decider=decider, client=object())

        found = agent.continue_from(TARGET, [kakao(ChangeField.PHONE, "053-567-3080")])

        assert found.proposed_changes == {"phone": "053-567-3080"}
