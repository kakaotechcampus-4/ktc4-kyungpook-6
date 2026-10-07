"""조사 예외 → 응답 `failure` 의 종류. 백엔드는 `code` 로 다시 조사할지 정한다."""

from __future__ import annotations

import pytest

from src.investigation import InvestigatorUnavailable
from src.investigation.failure import failure_of
from src.investigation.models import FailureCode
from src.investigation.web_research import ResearchParseError, ResearchTimeout, ResearchUngrounded


class RateLimited(Exception):
    code = 429


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (RateLimited("429 RESOURCE_EXHAUSTED. {'error': …}"), FailureCode.RATE_LIMITED),
        (ResearchTimeout("60초"), FailureCode.TIMEOUT),
        (ResearchParseError("형식 이탈"), FailureCode.BAD_RESPONSE),
        (ResearchUngrounded("그라운딩 없음"), FailureCode.BAD_RESPONSE),
        (InvestigatorUnavailable("GOOGLE_CLOUD_PROJECT 미설정"), FailureCode.UNAVAILABLE),
        (ValueError("예상 못 한 값"), FailureCode.ERROR),
    ],
)
def test_예외_종류마다_코드가_정해진다(error, code):
    assert failure_of(error).code is code


def test_예외_문장은_응답에_나가지_않는다():
    """SDK 오류 문장은 담당자가 읽을 말이 아니다 — 고정 문장으로 바꾼다."""
    failure = failure_of(RateLimited("429 RESOURCE_EXHAUSTED. {'error': {'code': 429}}"))

    assert "RESOURCE_EXHAUSTED" not in failure.message
