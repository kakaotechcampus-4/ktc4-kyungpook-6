"""조사 중 난 예외 → 응답의 `failure`(`code` + `message`).

예외 문장을 그대로 내보내지 않는다. SDK 오류 문장(`429 RESOURCE_EXHAUSTED. {'error': …}`)은 길고
담당자가 읽을 말이 아니며, 백엔드가 문장으로는 "다시 하면 될 실패인지"를 가를 수 없다. 원래 예외는
서버 로그에 남긴다.
"""

from __future__ import annotations

from src.investigation.models import Failure, FailureCode
from src.investigation.protocol import InvestigatorUnavailable
from src.investigation.web import _is_rate_limited
from src.investigation.web_research import ResearchParseError, ResearchTimeout

_MESSAGES = {
    FailureCode.RATE_LIMITED: "AI 요청 한도를 넘어 조사하지 못했습니다",
    FailureCode.TIMEOUT: "AI 응답이 제한 시간 안에 오지 않았습니다",
    FailureCode.BAD_RESPONSE: "AI 응답을 해석하지 못했습니다",
    FailureCode.UNAVAILABLE: "AI 조사 기능을 쓸 수 없는 상태입니다",
    FailureCode.ERROR: "조사 중 알 수 없는 오류가 났습니다",
}


def failure_of(error: Exception) -> Failure:
    if isinstance(error, InvestigatorUnavailable):
        code = FailureCode.UNAVAILABLE
    elif isinstance(error, ResearchTimeout):
        code = FailureCode.TIMEOUT
    elif isinstance(error, ResearchParseError):  # 형식 이탈·그라운딩 없음 — 재시도 끝에도 못 쓴 응답
        code = FailureCode.BAD_RESPONSE
    elif _is_rate_limited(error):
        code = FailureCode.RATE_LIMITED
    else:
        code = FailureCode.ERROR
    return Failure(code=code, message=_MESSAGES[code])
