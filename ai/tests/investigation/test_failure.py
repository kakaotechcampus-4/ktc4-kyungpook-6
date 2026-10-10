"""조사 예외 → 응답 `failure` 문장.

응답에는 **문장 하나만** 나간다(PROMPT-125). `FailureCode` 는 `failure.py` 안에만 있다.
그래서 여기서 보는 것은 "코드가 뭐로 정해지나" 가 아니라 **예외 종류마다 문장이 갈리나** 다 —
코드를 내보내지 않아도 분류 자체는 남아 있어야 하고, 그게 깨지면 담당자가 모든 실패에
같은 문장을 보게 된다.
"""

from __future__ import annotations

import pytest

from src.investigation import InvestigatorUnavailable
from src.investigation.failure import failure_of
from src.investigation.web_research import ResearchParseError, ResearchTimeout, ResearchUngrounded


class RateLimited(Exception):
    code = 429


@pytest.mark.parametrize(
    ("error", "message"),
    [
        (RateLimited("429 RESOURCE_EXHAUSTED. {'error': …}"), "AI 요청 한도를 넘어 조사하지 못했습니다"),
        (ResearchTimeout("60초"), "AI 응답이 제한 시간 안에 오지 않았습니다"),
        (ResearchParseError("형식 이탈"), "AI 응답을 해석하지 못했습니다"),
        (ResearchUngrounded("그라운딩 없음"), "AI 응답을 해석하지 못했습니다"),
        (InvestigatorUnavailable("GOOGLE_CLOUD_PROJECT 미설정"), "AI 조사 기능을 쓸 수 없는 상태입니다"),
        (ValueError("예상 못 한 값"), "조사 중 알 수 없는 오류가 났습니다"),
    ],
)
def test_예외_종류마다_문장이_정해진다(error, message):
    assert failure_of(error) == message


def test_예외_문장은_응답에_나가지_않는다():
    """SDK 오류 문장은 담당자가 읽을 말이 아니다 — 고정 문장으로 바꾼다."""
    assert "RESOURCE_EXHAUSTED" not in failure_of(RateLimited("429 RESOURCE_EXHAUSTED. {'error': {'code': 429}}"))


def test_응답에_나가는_값은_문자열이다():
    """백엔드는 `String failure` 로 받는다. 객체로 돌아가면 Jackson 이 못 넣어 계약 오류가 된다."""
    assert isinstance(failure_of(ValueError("아무거나")), str)
