"""네이버 블로그 검색 API 실험 — **로컬 실험용**. `naver_browse` 의 A안 비교군.

브라우저 실험(`naver_browse`)은 검색 결과를 긁고 글 본문까지 연다. 이 스크립트는 검색만 네이버
블로그 검색 API(NAVER API HUB)로 바꾸고 **본문은 열지 않는다**. API 가 주는 제목·요약·작성일만으로 브라우저 실험과
비슷한 관측이 나오는지 잰다.

    LLM ⇄ 도구 하나 (search_blog = 블로그 검색 API, 최신순·정확도순 합쳐 최신 글부터) — 최대 MAX_STEPS 회
      → 최종 답: `항목 | 값 | 근거 문장 | URL` 줄
      → 검증: URL 은 검색 결과에 나온 글이어야 하고, 근거 문장은 그 결과의 제목·요약에 그대로 있어야 한다
              작성일은 API 의 postdate
      → 담당자 확인일(`--checked-at`) 이전 글의 관측은 뺌 (`mark_stale`)
      → 항목마다 가장 최근 글의 관측만 남김 (`latest_only`)
      → `classify()` (운영과 같은 규칙)

검색 API 는 티스토리 등 네이버 밖 블로그도 돌려준다. 출처 도메인은 URL 에서 읽는다.

**약관** — 2026-09-07 개정 검색 API 약관은 AI 입력·저장을 금지한다. 운영에 넣지 않는 일회성 비교
실험이고, 결과 시트는 `eval/fixtures/`(커밋 금지)에만 쓴다.

사용법:
    uv run python -m eval.naver_api run --tag a1
    uv run python -m eval.naver_api run --tag a1 --ids 2,4,7
    uv run python -m eval.naver_api rescore --tag a3 --from a2 --checked-at 2025-10-05
    uv run python -m eval.naver_api show --tag a1
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import os
import re
import time
from dataclasses import replace
from pathlib import Path
from urllib.parse import urlparse

import httpx

from eval import mock_db
from eval.mock_round import change_summary
from eval.naver_browse import (
    COLUMNS,
    LLM_TIMEOUT_SECONDS,
    MAX_STEPS,
    MODEL,
    STORE_PAUSE_SECONDS,
    _compact,
    _dump,
    _force_utf8_output,
    _post_url,
    _targets,
    show,
)
from src.investigation import InvestigationTarget
from src.investigation.classify import classify
from src.investigation.models import ChangeField
from src.investigation.web import RATE_LIMIT_WAITS_SECONDS, _is_rate_limited
from src.investigation.web_research import NOTHING_FOUND, Observation, ResearchResult, Source, _parse_line

#: 개발자센터 검색 API 는 2026-07-31 로 신규 발급이 끝나 NCP 의 NAVER API HUB 로 옮겨 갔다. 응답 형식은 같다.
API_URL = "https://naverapihub.apigw.ntruss.com/search/v1/blog"
SEARCH_RESULT_LIMIT = 10
API_PAUSE_SECONDS = 0.2
TAG = re.compile(r"<[^>]+>")


def _sheet(tag: str) -> Path:
    return mock_db.FIXTURES / f"naver_api_{tag}.csv"


def _clean(text: str) -> str:
    """API 는 검색어를 <b> 로 감싸고 HTML 엔티티를 그대로 준다."""
    return re.sub(r"\s+", " ", html.unescape(TAG.sub("", text))).strip()


class NaverBlogApi:
    def __init__(self) -> None:
        self._http = httpx.Client(
            headers={
                "X-NCP-APIGW-API-KEY-ID": os.environ["NAVER_CLIENT_ID"],
                "X-NCP-APIGW-API-KEY": os.environ["NAVER_CLIENT_SECRET"],
            },
            timeout=10.0,
        )

    def close(self) -> None:
        self._http.close()

    def search(self, query: str) -> list[dict[str, str]]:
        """최신순·정확도순 결과를 합쳐 최신 글부터 돌려준다. 모델이 최신순을 잘 안 고르고(a1: 86번 중 30번),
        정확도순만 보면 2019~23년 글이 근거가 된다."""
        merged: dict[str, dict[str, str]] = {}
        for sort in ("date", "sim"):
            for r in self._search(query, sort):
                merged.setdefault(r["url"], r)
        return sorted(merged.values(), key=lambda r: r["date"], reverse=True)

    def _search(self, query: str, sort: str) -> list[dict[str, str]]:
        response = self._http.get(API_URL, params={"query": query, "display": SEARCH_RESULT_LIMIT, "sort": sort})
        response.raise_for_status()
        time.sleep(API_PAUSE_SECONDS)
        results = []
        for item in response.json().get("items", []):
            d = item.get("postdate", "")
            results.append(
                {
                    "url": _post_url(item["link"]) or item["link"],
                    "date": f"{d[:4]}-{d[4:6]}-{d[6:8]}" if len(d) == 8 else "",
                    "title": _clean(item.get("title", "")),
                    "summary": _clean(item.get("description", "")),
                }
            )
        return results


def build_prompt(target: InvestigationTarget) -> str:
    return f"""너는 가게 정보를 확인하는 조사원이다. 네이버 블로그 검색 결과를 보고 아래 가게의 **현재** 정보를 찾아라.

가게 이름: {target.name}
주소: {target.address or "(모름)"}
전화번호: {target.phone or "(모름)"}

도구:
- search_blog(query): 네이버 블로그 검색. 결과는 최신 글부터 오고, 결과마다 URL·작성일·제목·요약이 온다.
  글 본문은 볼 수 없다

조사 방법:
- 상호명과 지역(구·동 이름)으로 검색해라. 결과가 이 가게가 아니면 검색어를 바꿔라
  (지점명 빼기, "식당"·"점" 같은 꼬리 빼기, 동 이름 넣기, 업종 넣기, "폐업" 넣기)
- 주소·지점이 다르면 이름이 같아도 다른 가게다. 그 결과의 값은 적지 마라
- 가게 정보는 바뀐다. 최근 글을 중심으로 봐라. 오래된 글만 나오면 검색어를 바꿔 최근 글을 더 찾아봐라

확인할 것 (항목 이름):
- status: 영업 상태. 영업 중이면 OPEN, 휴업이면 SUSPENDED, 폐업이면 CLOSED
- phone: 전화번호
- address: 도로명 주소
- name: 상호명

다 봤으면 찾은 것마다 한 줄씩 아래 형식으로만 적어라. 다른 문장은 쓰지 마라.
항목 | 값 | 근거 문장 | URL

규칙:
- 근거 문장은 **검색 결과의 제목이나 요약에서 그대로 복사**한 짧은 구절이다(80자 이내). 고치거나 요약하지 마라
- URL 은 그 근거가 나온 검색 결과의 URL 이다
- 결과마다 따로 적어라. 두 결과에서 같은 값을 봤으면 두 줄이다
- 찾은 것이 없으면 "{NOTHING_FOUND}" 한 단어만 적어라
- 바뀌었는지 판단하지 마라. 본 것만 적어라"""


def latest_only(observations: list[Observation]) -> list[Observation]:
    """항목마다 가장 최근 작성일의 관측만 남긴다. `classify()` 는 출처 도메인 수로 값을 고르는데, 블로그는
    도메인이 늘 하나라 날짜가 다른 값이 맞서면 아무거나 고른다(돈뼈락: 2019 번호 vs 2020 번호)."""
    newest: dict[object, str] = {}
    for o in observations:
        if o.sources and o.observed_at > newest.get(o.field, ""):
            newest[o.field] = o.observed_at
    return [o for o in observations if not o.sources or o.observed_at == newest.get(o.field, "")]


#: `lastCheckedAt`(담당자 확인 시각) 대신 쓰는 실험용 기준일. 간이 DB 에는 확인 시각이 없다.
DEFAULT_CHECKED_AT = "2025-10-05"
STALE = "확인일 이전 글"


def mark_stale(checked: list[dict[str, object]], checked_at: str) -> list[dict[str, object]]:
    """담당자가 확인한 날보다 이전(또는 날짜 모름) 글의 관측은 판정에서 뺀다 — 그 뒤에 사람이 본 DB 값이 더 믿을 만하다."""
    out = []
    for c in checked:
        o = c["observation"]
        if not c["problem"] and o.observed_at < checked_at:
            c = {"observation": replace(o, sources=()), "problem": STALE}
        out.append(c)
    return out


def score(target: InvestigationTarget, checked: list[dict[str, object]], checked_at: str | None):
    """검증된 관측 → (확인일 필터) → 항목별 최신 글만 → `classify()`."""
    if checked_at:
        checked = mark_stale(checked, checked_at)
    return classify(target, ResearchResult(latest_only([c["observation"] for c in checked]))), checked


def _parse_answer(text: str, seen: dict[str, dict[str, str]]) -> list[dict[str, object]]:
    """최종 답을 관측으로 읽고 하나씩 검증한다. 근거가 검색 결과의 제목·요약에 있어야 출처를 붙인다."""
    if text.strip().strip("\"'.") == NOTHING_FOUND:
        return []
    out = []
    for line in text.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 4:
            continue
        observation = _parse_line(" | ".join(parts[:3]))
        if observation is None:
            continue
        url = _post_url(parts[3]) or parts[3]
        result = seen.get(url)
        if result is None:
            problem = "검색 결과에 없는 글"
        elif _compact(observation.evidence) not in _compact(result["title"] + result["summary"]):
            problem = "요약에 없는 근거"
        else:
            problem = ""
        out.append(
            {
                "observation": Observation(
                    field=observation.field,
                    value=observation.value,
                    evidence=observation.evidence,
                    observed_at=result["date"] if result else "",
                    sources=() if problem else (Source(domain=urlparse(url).netloc, url=url),),
                ),
                "problem": problem,
            }
        )
    return out


class NaverApiAgent:
    """`naver_browse.NaverBlogAgent` 와 같은 수동 함수 호출 루프. 도구는 검색 하나뿐이다."""

    def __init__(self, api: NaverBlogApi, *, model: str = MODEL) -> None:
        from google import genai

        self._api = api
        self._model = model
        self._client = genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )

    def _generate(self, contents: list, config: object, steps: list[str]) -> object:
        waits = iter(RATE_LIMIT_WAITS_SECONDS)
        while True:
            try:
                return self._client.models.generate_content(model=self._model, contents=contents, config=config)
            except Exception as e:
                wait = next(waits, None) if _is_rate_limited(e) else None
                if wait is None:
                    raise
                steps.append(f"429 — {wait:.0f}초 대기")
                time.sleep(wait)

    def investigate(self, target: InvestigationTarget, steps: list[str]) -> list[dict[str, object]]:
        from google.genai import types

        seen: dict[str, dict[str, str]] = {}  # URL → 검색 결과
        api = self._api

        def search_blog(query: str) -> str:
            """네이버 블로그를 검색한다. 최신 글부터 URL, 작성일, 제목, 요약을 돌려준다."""
            results = api.search(query)
            for r in results:
                seen[r["url"]] = r
            steps.append(f"검색 {query!r} → {len(results)}건")
            if not results:
                return "검색 결과 없음"
            return "\n".join(
                f"{i + 1}. {r['url']} ({r['date'] or '날짜 모름'}) {r['title']} — {r['summary']}"
                for i, r in enumerate(results)
            )

        http_options = types.HttpOptions(timeout=int(LLM_TIMEOUT_SECONDS * 1000))
        with_tools = types.GenerateContentConfig(
            tools=[search_blog],
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            http_options=http_options,
        )
        contents: list[types.Content] = [types.Content(role="user", parts=[types.Part(text=build_prompt(target))])]
        calls = 0
        while True:
            config = with_tools if calls < MAX_STEPS else types.GenerateContentConfig(http_options=http_options)
            response = self._generate(contents, config, steps)
            contents.append(response.candidates[0].content)
            function_calls = response.function_calls or []
            if not function_calls or calls >= MAX_STEPS:
                break
            replies = []
            for call in function_calls:
                calls += 1
                result = search_blog(**(call.args or {})) if call.name == "search_blog" else f"모르는 도구: {call.name}"
                replies.append(types.Part.from_function_response(name=call.name, response={"result": result}))
            if calls >= MAX_STEPS:
                steps.append("상한 도달 — 정리 요청")
                replies.append(types.Part(text="검색은 그만하고 지금까지 본 결과로 형식대로 적어라."))
            contents.append(types.Content(role="user", parts=replies))
        text = response.text or ""
        steps.append("답:\n" + text.strip())
        return _parse_answer(text, seen)


def run(tag: str, *, base_tag: str, ids: list[str] | None, limit: int | None, checked_at: str | None) -> int:
    sheet = _sheet(tag)
    kept = []
    if sheet.exists():
        with sheet.open(encoding="utf-8-sig", newline="") as f:
            kept = [r for r in csv.DictReader(f) if r["AI판정"] != "실패"]
    done = {r["storeId"] for r in kept}
    targets = [(row, t) for row, t in _targets(base_tag, ids, limit) if str(t.store_id) not in done]
    print(f"이미 한 것 {len(done)}곳 · 남은 것 {len(targets)}곳", flush=True)

    api = NaverBlogApi()
    agent = NaverApiAgent(api)
    try:
        for i, (row, target) in enumerate(targets):
            out = {"storeId": str(target.store_id), "그룹": row["answer_group"], "사업장명": target.name,
                   "주소": target.address or ""}
            started = time.time()
            steps: list[str] = []
            try:
                found, checked = score(target, agent.investigate(target, steps), checked_at)
                out |= {
                    "AI판정": found.classification.value,
                    "수정안": "; ".join(f"{k}={v}" for k, v in found.proposed_changes.items()),
                    "신호": change_summary(found),
                    "관측": _dump(checked),
                }
            except Exception as e:  # noqa: BLE001 - 한 건 실패로 표본 전체를 버리지 않는다
                out["AI판정"], out["실패"] = "실패", f"{type(e).__name__}: {e}"
            out["단계"] = "\n".join(steps)
            out["초"] = f"{time.time() - started:.0f}"
            kept.append(out)
            with sheet.open("w", encoding="utf-8-sig", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=COLUMNS)
                writer.writeheader()
                writer.writerows(kept)
            verified = sum(1 for o in json.loads(out.get("관측") or "[]") if not o["problem"])
            print(
                f"[{i + 1:2}/{len(targets)}] {out['그룹']} {target.name} → {out['AI판정']} "
                f"관측 {verified}건 {out.get('수정안', '')} ({out['초']}s, 단계 {len(steps)})",
                flush=True,
            )
            time.sleep(STORE_PAUSE_SECONDS)
    finally:
        api.close()
    print(f"→ {sheet}")
    return show(tag, _sheet(tag))


def rescore(tag: str, *, source_tag: str, checked_at: str) -> int:
    """다시 검색하지 않고 `source_tag` 시트의 관측에 판정 규칙만 다시 적용한다. 검색 변동 없이 규칙 효과만 본다."""
    db = {str(r["storeId"]): r for r in mock_db.load()}
    with _sheet(source_tag).open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    for row in rows:
        if row["AI판정"] == "실패":
            continue
        checked = [
            {
                "observation": Observation(
                    field=ChangeField(o["field"]),
                    value=o["value"],
                    evidence=o["evidence"],
                    observed_at=o["observed_at"],
                    sources=tuple(Source(**s) for s in o["sources"]),
                ),
                "problem": o["problem"],
            }
            for o in json.loads(row["관측"] or "[]")
        ]
        found, checked = score(InvestigationTarget.model_validate(db[row["storeId"]]), checked, checked_at)
        row |= {
            "AI판정": found.classification.value,
            "수정안": "; ".join(f"{k}={v}" for k, v in found.proposed_changes.items()),
            "신호": change_summary(found),
            "관측": _dump(checked),
        }
    with _sheet(tag).open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"{source_tag} → {tag} (확인일 {checked_at})")
    return show(tag, _sheet(tag))


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="네이버 블로그 검색 API 실험 (본문 없이 요약만)")
    sub = parser.add_subparsers(dest="command", required=True)
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--tag", required=True)
    run_parser.add_argument("--base", default="v9", help="근거 0건 가게를 고를 그라운딩 시트 태그")
    run_parser.add_argument("--ids", help="storeId 목록(쉼표). 주면 --base 를 무시한다")
    run_parser.add_argument("--limit", type=int)
    run_parser.add_argument("--checked-at", help="담당자 확인일(YYYY-MM-DD). 이보다 이전 글은 판정에서 뺀다")
    rescore_parser = sub.add_parser("rescore", help="다시 검색하지 않고 판정 규칙만 다시 적용")
    rescore_parser.add_argument("--tag", required=True)
    rescore_parser.add_argument("--from", dest="source_tag", required=True)
    rescore_parser.add_argument("--checked-at", default=DEFAULT_CHECKED_AT)
    sub.add_parser("show").add_argument("--tag", required=True)
    args = parser.parse_args()
    if args.command == "run":
        ids = [i.strip() for i in args.ids.split(",")] if args.ids else None
        return run(args.tag, base_tag=args.base, ids=ids, limit=args.limit, checked_at=args.checked_at)
    if args.command == "rescore":
        return rescore(args.tag, source_tag=args.source_tag, checked_at=args.checked_at)
    return show(args.tag, _sheet(args.tag))


if __name__ == "__main__":
    raise SystemExit(main())
