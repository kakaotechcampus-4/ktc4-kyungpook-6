"""웹검색으로 가게의 **현재 정보를 관측**한다 — 2차 조사의 수집 단계.

모델에게 "바뀌었는가"를 판단시키지 않는다. 웹에 적힌 값(영업 상태·전화번호·주소·상호명)을
근거 문장과 함께 **있는 그대로 적어 오게** 하고, 우리 DB 값과의 비교와 분류는 `classify.py` 가
규칙으로 한다. 모델의 자기판단이 분류에 섞이면 왜 그렇게 나왔는지 설명할 수 없다.

**출처는 모델에게 받지 않는다.** 실제로 돌려 보니 모델이 적은 URL 15개 중 13개가 검색 결과에
없는 주소였다. 대신 구글이 붙여 주는 `grounding_supports`(응답의 어느 바이트 구간이 어느 검색
결과에 근거하는가)로 관측 줄과 검색 결과를 잇는다(`parse_observations`). 이어지지 않는 관측은
출처가 없는 것으로 남고, 분류에서 세지 않는다.

**응답은 JSON 이 아니라 한 줄에 관측 하나(`항목 | 값 | 근거 | 날짜`)로 받는다.** JSON 으로
받으면 검색은 하면서도 그라운딩 결과가 거의 붙지 않았다(실측 9번 중 1번). 줄 형식은 6번 중
6번 붙었다. 그라운딩과 스키마 강제는 어차피 같이 못 써서(`biz_number/vertex.py`) JSON 의
이점도 없다.
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass, field, replace
from typing import Protocol

import httpx

from src.investigation.models import ChangeField, InvestigationTarget

# 프롬프트를 고치면 올린다 — 측정 캐시가 낡은 응답과 섞이지 않게 하는 용도.
#   v1: JSON, 모델이 source_url 을 적음
#   v2: source_url 제거, 출처는 그라운딩으로 잇는다
#   v3: JSON 대신 줄 형식 — JSON 이면 그라운딩이 거의 안 붙는다
RESEARCH_PROMPT_VERSION = "v3"

#: 모델이 상태를 적을 때 쓰는 값. `StoreStatus` 중 웹에서 관측할 수 있는 것만 연다.
OBSERVABLE_STATUSES = ("OPEN", "SUSPENDED", "CLOSED")

#: 모델이 상태 값을 한글로 적어 올 때. 뜻이 하나로 정해지는 말만 받는다 — "영업 종료"는 마감 시각
#: ("21:20에 영업 종료", 실측)으로도 쓰이고 "휴무"는 정기 휴일이라 받지 않는다. 공백은 지우고 비교한다.
_STATUS_WORDS = {
    "영업": "OPEN",
    "영업중": "OPEN",
    "정상영업": "OPEN",
    "운영중": "OPEN",
    "휴업": "SUSPENDED",
    "휴업중": "SUSPENDED",
    "임시휴업": "SUSPENDED",
    "폐업": "CLOSED",
    "폐점": "CLOSED",
}

#: 찾은 것이 없을 때 모델이 적는 답. 형식 이탈과 구분하려고 따로 정해 둔다.
NOTHING_FOUND = "없음"

# 그라운딩 리다이렉트 주소를 원래 URL 로 풀 때. 못 풀면 리다이렉트 주소를 그대로 쓴다.
REDIRECT_TIMEOUT_SECONDS = 5.0

# LLM 호출 한 번의 상한. 평소 5~40초라 60초로 둔다. 걸지 않으면 한 건이 응답을 안 줄 때
# POST /investigations 요청이 통째로 매달린다(동기 엔드포인트라 워커도 묶인다). SDK 는
# retry_options 를 주지 않으면 재시도하지 않으므로 이 값이 곧 호출 한 번의 최대 시간이다.
LLM_TIMEOUT_SECONDS = 60.0

logger = logging.getLogger(__name__)


def build_research_prompt(target: InvestigationTarget) -> str:
    """가게의 현재 정보를 웹에서 찾아 **관측값 목록**으로 달라고 묻는다.

    우리 DB 값을 함께 주는 이유 — 같은 이름의 다른 지점을 가려내려면 주소·전화번호가
    필요하다. 대신 "DB 값과 다른 것만 적어라"고 하지 않는다. 같은 값을 확인한 근거도
    출처끼리 엇갈리는지 가리는 데 쓴다.
    """
    return f"""다음 가게의 **현재** 정보를 웹 검색으로 확인해줘.

가게 이름: {target.name}
주소: {target.address or "(모름)"}
전화번호: {target.phone or "(모름)"}

확인할 것 (항목 이름):
- status: 영업 상태. 영업 중이면 OPEN, 휴업이면 SUSPENDED, 폐업이면 CLOSED
- phone: 현재 전화번호
- address: 현재 도로명 주소. 다른 곳으로 이전했다면 이전한 주소
- name: 현재 상호명. 상호를 바꿨다면 바뀐 이름

찾은 것마다 한 줄씩 아래 형식으로만 적어라. 다른 문장은 쓰지 마라.
항목 | 값 | 근거 문장 | 날짜

규칙:
- **이 가게에 대한 검색 결과만** 적어라. 이름이 같은 다른 지점이면 빼라
- 근거 문장은 검색 결과에서 그 값을 보여 주는 문장이다. 여러 출처를 한 문장에 섞지 마라
- 출처마다 따로 적어라. 두 곳에서 같은 값을 봤으면 두 줄이다
- 근거를 댈 수 없으면 적지 마라. 추측하거나 지어내지 마라
- 날짜는 근거에 게시일·리뷰 작성일 등이 있으면 YYYY-MM-DD, 없으면 비워 둬라
- 찾은 것이 없으면 "{NOTHING_FOUND}" 한 단어만 적어라
- 바뀌었는지 판단하지 마라. 본 것만 적어라"""


#: 줄 앞의 목록 기호("- ", "1. ", "* ")는 모델이 자주 붙인다.
_LIST_MARKER = re.compile(r"^\s*(?:[-*•]|\d+[.)])\s*")

#: 응답의 항목 이름 → 가게 필드. 프롬프트는 짧은 이름을 쓰고 결과는 백엔드 필드명으로 담는다.
_FIELDS = {
    "status": ChangeField.STATUS,
    "phone": ChangeField.PHONE,
    "address": ChangeField.ADDRESS,
    "name": ChangeField.NAME,
}


@dataclass(frozen=True)
class Source:
    """그라운딩 검색 결과 하나."""

    domain: str
    url: str


@dataclass(frozen=True)
class Support:
    """응답 텍스트의 바이트 구간 [start, end) 가 어느 검색 결과(청크 번호)에 근거하는가."""

    start: int
    end: int
    chunk_indices: tuple[int, ...]


@dataclass(frozen=True)
class Observation:
    """웹에서 본 값 하나. 판단이 아니라 관측이다."""

    field: ChangeField
    value: str
    evidence: str
    observed_at: str = ""  # YYYY-MM-DD · YYYY-MM · YYYY(`normalize_date`). 모르면 빈 문자열
    #: 이 근거 문장을 뒷받침하는 검색 결과. 비어 있으면 출처를 확인하지 못한 관측이다.
    sources: tuple[Source, ...] = ()
    #: 값을 수정안·근거 문구에 담아도 되는가. 카카오 로컬 값은 저장할 수 없다(실시간 비교 후 폐기만 허용) —
    #: 판정에는 쓰되, 수정안에는 값 대신 "지도 등록 정보와 다름 + 링크"만 남긴다.
    storable: bool = True

    @property
    def domains(self) -> frozenset[str]:
        return frozenset(s.domain for s in self.sources)


@dataclass(frozen=True)
class ResearchResult:
    observations: list[Observation] = field(default_factory=list)
    #: 모델이 실제로 쓴 검색어(`web_search_queries`). 판정에는 쓰지 않고, 검색이 흔들린 것인지
    #: 추출이 흔들린 것인지 가리는 데 쓴다(`docs/2차_조사.md`).
    queries: tuple[str, ...] = ()


class ResearchParseError(Exception):
    """모델 응답이 약속한 형식이 아닐 때. 재시도 대상이다."""


class ResearchUngrounded(ResearchParseError):
    """관측은 있는데 그라운딩 검색 결과가 하나도 붙지 않았을 때. 재시도 대상이다.

    이대로 분류하면 관측 전부가 "출처 없음"으로 빠져 **변화없음으로 조용히 나간다** —
    바뀐 가게를 놓치는 쪽으로 틀리므로 실패로 올린다.
    """


class ResearchTimeout(Exception):
    """LLM 호출이 `LLM_TIMEOUT_SECONDS` 안에 끝나지 않았을 때. **재시도하지 않는다** — 한 번 더 기다리면
    한 건이 두 배로 늘어난다. 그 가게만 실패로 남기고 나머지를 계속 조사한다."""


class ResearchProvider(Protocol):
    def research(self, target: InvestigationTarget) -> ResearchResult: ...


def normalize_domain(host: str) -> str:
    host = host.lower().strip().rstrip(".")
    return host[4:] if host.startswith("www.") else host


def _squash_nothing(value: str) -> bool:
    """값 자리에 "없음"·"정보 없음"·"-" 같은 빈 표시가 왔는가."""
    # "(모름)" 은 프롬프트가 DB 값이 없을 때 쓰는 표시인데, 모델이 그대로 옮겨 오기도 한다(실측).
    return value.strip(" .-()") in ("", NOTHING_FOUND, "정보 없음", "정보없음", "모름", "N/A")


#: 근거의 게시일. "2025-07-22", "2025.7.22", "2025년 7월", "2025" 를 받는다.
_DATE = re.compile(r"(?<!\d)(20\d{2})(?:\s*[-./년]\s*(\d{1,2}))?(?:\s*[-./월]\s*(\d{1,2}))?")


def normalize_date(text: str) -> str:
    """모델이 적은 날짜를 "YYYY-MM-DD" · "YYYY-MM" · "YYYY" 로 고친다. 날짜가 아니면 빈 문자열.

    모델은 날짜 칸에 "맛집검색" 같은 엉뚱한 말을 적기도 한다(실측) — 그대로 두면 날짜 비교에서
    어떤 날짜보다도 "나중"으로 정렬된다.
    """
    match = _DATE.search(text)
    if not match:
        return ""
    parts = [match[1]]
    for piece, limit in ((match[2], 12), (match[3], 31)):
        if piece is None or not 1 <= int(piece) <= limit:
            break
        parts.append(f"{int(piece):02}")
    return "-".join(parts)


def _parse_line(line: str) -> Observation | None:
    """한 줄을 관측으로 읽는다. 약속을 어긴 줄(모르는 항목, 빈 근거, 엉뚱한 상태 값)은 None."""
    parts = [part.strip() for part in _LIST_MARKER.sub("", line).split("|")]
    if len(parts) < 3:
        return None
    change_field = _FIELDS.get(parts[0].lower())
    value, evidence = parts[1], parts[2]
    if change_field is None or not value or not evidence:
        return None
    # "phone | 없음 | 전화없음." — 값이 없다는 관측이지 새 값이 아니다(실측).
    if _squash_nothing(value):
        return None
    if change_field is ChangeField.STATUS:
        value = _STATUS_WORDS.get(re.sub(r"\s+", "", value), value.upper())
        if value not in OBSERVABLE_STATUSES:
            return None
    return Observation(
        field=change_field,
        value=value,
        evidence=evidence,
        observed_at=normalize_date(parts[3]) if len(parts) > 3 else "",
    )


def parse_observations(
    text: str,
    chunks: list[Source] | None = None,
    supports: list[Support] | None = None,
) -> list[Observation]:
    """모델 응답을 관측 목록으로 바꾸고, 줄마다 그 줄을 뒷받침하는 검색 결과를 출처로 붙인다.

    **줄의 바이트 구간과 겹치는 support 의 청크가 그 줄의 출처다.** support 구간은 응답 원문
    기준 UTF-8 바이트 오프셋이다 — 실측 40/40, 문자 오프셋으로는 0/40. 근거 문장을 텍스트로
    찾아 잇지 않는 이유 — `name | 본점 | 본점 |` 처럼 값과 근거가 같은 줄이 흔해서, 찾은 위치가
    앞줄로 밀려 출처가 엉뚱한 관측에 붙었다(실측에서 namu.wiki 출처가 사라짐).

    약속을 어긴 줄은 그 줄만 뺀다. 형식에 맞는 줄이 하나도 없는데 "없음"도 아니면
    `ResearchParseError` 다. 모델이 "없음" 한 단어 대신 항목마다 `status | 없음` 처럼
    적어 오기도 해서(실측), 그런 줄만 있으면 찾은 것이 없는 것으로 본다.
    """
    if text.strip().strip("\"'.") == NOTHING_FOUND:
        return []
    chunks = chunks or []
    supports = supports or []

    observations = []
    said_nothing = False
    offset = 0
    for line in text.splitlines(keepends=True):
        start, offset = offset, offset + len(line.encode("utf-8"))
        observation = _parse_line(line)
        if observation is None:
            said_nothing = said_nothing or _says_nothing_found(line)
            continue
        indices: list[int] = []
        for s in supports:
            if s.start < offset and s.end > start:
                indices.extend(i for i in s.chunk_indices if i not in indices and 0 <= i < len(chunks))
        observations.append(replace(observation, sources=tuple(chunks[i] for i in indices)))
    if not observations and not said_nothing:
        raise ResearchParseError(f"형식에 맞는 줄이 없습니다: {text[:100]!r}")
    return observations


def _says_nothing_found(line: str) -> bool:
    """찾은 것이 없다는 줄인가 — `status | 없음` 처럼 항목별로 적었거나, 형식 머리줄
    (`항목 | 값 | 근거 문장 | 날짜`) 아래에 "없음" 한 단어만 적었거나(실측: 갑이회수산)."""
    if line.strip().strip("\"'.") == NOTHING_FOUND:
        return True
    parts = [part.strip() for part in _LIST_MARKER.sub("", line).split("|")]
    return len(parts) >= 2 and parts[0].lower() in _FIELDS and parts[1] == NOTHING_FOUND


def search_queries_of(response: object) -> tuple[str, ...]:
    """SDK 응답에서 모델이 쓴 검색어를 꺼낸다. 없으면 빈 튜플."""
    candidates = getattr(response, "candidates", None) or []
    metadata = getattr(candidates[0], "grounding_metadata", None) if candidates else None
    return tuple(getattr(metadata, "web_search_queries", None) or ())


def grounding_of(response: object) -> tuple[list[Source], list[Support]]:
    """SDK 응답에서 검색 결과(청크)와 support 를 꺼낸다.

    청크의 `uri` 는 구글 리다이렉트 주소라 도메인은 `domain`, 없으면 `title`(보통 도메인이
    들어 있다)에서 얻는다. URL 은 여기서 풀지 않는다 — 출처로 쓰인 것만 나중에 푼다.
    """
    candidates = getattr(response, "candidates", None) or []
    metadata = getattr(candidates[0], "grounding_metadata", None) if candidates else None
    chunks = []
    for chunk in getattr(metadata, "grounding_chunks", None) or []:
        web = getattr(chunk, "web", None)
        host = (getattr(web, "domain", None) or getattr(web, "title", None) or "") if web else ""
        chunks.append(Source(domain=normalize_domain(host), url=getattr(web, "uri", "") or ""))
    supports = []
    for s in getattr(metadata, "grounding_supports", None) or []:
        segment = getattr(s, "segment", None)
        if segment is None or segment.end_index is None:
            continue
        supports.append(
            Support(
                start=segment.start_index or 0,
                end=segment.end_index,
                chunk_indices=tuple(getattr(s, "grounding_chunk_indices", None) or ()),
            )
        )
    return chunks, supports


def resolve_redirect(url: str, http: httpx.Client) -> str:
    """그라운딩 리다이렉트 주소를 원래 URL 로 푼다. 리다이렉트 주소는 시간이 지나면 만료된다.

    못 풀면 받은 주소를 그대로 돌려준다 — 링크가 낡는 것이 근거를 잃는 것보다 낫다.
    """
    try:
        response = http.get(url, follow_redirects=False)
    except httpx.HTTPError as e:
        logger.info("리다이렉트를 풀지 못했습니다: %s (%s)", url[:80], e)
        return url
    return response.headers.get("location") or url


class VertexResearchProvider:
    """Vertex AI 구글 그라운딩 검색으로 관측값을 모은다. ADC 로 인증한다."""

    def __init__(
        self,
        model: str = "gemini-2.5-flash",
        *,
        client: object | None = None,
        http: httpx.Client | None = None,
    ) -> None:
        self._model = model
        self._client = client if client is not None else self._build_client()
        self._http = http if http is not None else httpx.Client(timeout=REDIRECT_TIMEOUT_SECONDS)

    @staticmethod
    def _build_client() -> object:
        from google import genai

        return genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )

    def research(self, target: InvestigationTarget) -> ResearchResult:
        return self.research_with_prompt(build_research_prompt(target))

    def research_with_prompt(self, prompt: str) -> ResearchResult:
        """프롬프트를 바꿔 같은 방식(그라운딩 검색 → 줄 파싱 → 출처 잇기)으로 관측을 모은다.
        에이전트의 목적을 좁힌 웹검색(`agent.py`)이 쓴다."""
        from google.genai import types

        try:
            response = self._client.models.generate_content(
                model=self._model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    tools=[{"google_search": {}}],
                    http_options=types.HttpOptions(timeout=int(LLM_TIMEOUT_SECONDS * 1000)),
                ),
            )
        except httpx.TimeoutException as e:
            raise ResearchTimeout(f"LLM 응답이 {LLM_TIMEOUT_SECONDS:.0f}초 안에 오지 않았습니다: {e}") from e
        chunks, supports = grounding_of(response)
        observations = parse_observations(response.text or "", chunks, supports)
        if observations and not chunks:
            raise ResearchUngrounded(f"관측 {len(observations)}건에 그라운딩 검색 결과가 없습니다")
        return ResearchResult(observations=self._with_real_urls(observations), queries=search_queries_of(response))

    def _with_real_urls(self, observations: list[Observation]) -> list[Observation]:
        """출처로 쓰인 리다이렉트 주소만 원래 URL 로 바꾼다. 같은 주소는 한 번만 푼다."""
        resolved: dict[str, str] = {}
        for o in observations:
            for s in o.sources:
                if s.url and s.url not in resolved:
                    resolved[s.url] = resolve_redirect(s.url, self._http)
        return [
            replace(o, sources=tuple(replace(s, url=resolved.get(s.url, s.url)) for s in o.sources))
            for o in observations
        ]


class MockResearchProvider:
    """테스트·로컬 확인용. 정해 둔 결과를 돌려준다."""

    def __init__(self, result: ResearchResult | None = None) -> None:
        self._result = result or ResearchResult()

    def research(self, target: InvestigationTarget) -> ResearchResult:
        return self._result
