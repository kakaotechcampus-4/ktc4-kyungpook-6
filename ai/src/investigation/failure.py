"""조사 중 난 예외 → 응답의 `failure` 문장.

예외 문장을 그대로 내보내지 않는다. SDK 오류 문장(`429 RESOURCE_EXHAUSTED. {'error': …}`)은 길고
담당자가 읽을 말이 아니다. 원래 예외는 서버 로그에 남긴다.

**`FailureCode` 는 여기서만 쓰고 응답에는 넣지 않는다**(PROMPT-125). 백엔드는 `String failure` 로
받아 로그와 `Job.errorMessage` 에만 쓰고, 다시 조사할지는 코드가 아니라 **예외**로 가른다.
나중에 백엔드가 코드별로 갈라야 하면 여기 분류가 그대로 있으니 응답 모양만 되돌리면 된다.
"""

from __future__ import annotations

from src.investigation.models import FailureCode
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


def failure_of(error: Exception) -> str:
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
    return _MESSAGES[code]
