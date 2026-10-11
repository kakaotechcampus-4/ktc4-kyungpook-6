"""네이버 블로그 브라우저 에이전트 — **로컬 실험용 프로토타입**. 운영 코드(`src/`)에 넣지 않는다.

구글 그라운딩은 네이버 블로그를 출처로 거의 주지 않는다(간이 DB 60곳에서 0건). 네이버 robots.txt 가
`Google-Extended` 를 막아 두었기 때문으로 보인다. 그래서 LLM 이 실제 브라우저(Playwright)를 몰아
네이버 블로그를 직접 검색하고 읽게 하면 관측이 늘어나는지 잰다.

    LLM ⇄ 도구 두 개 (search_blog, read_post) — 최대 MAX_STEPS 회
      → 최종 답: `항목 | 값 | 근거 문장 | URL` 줄
      → 검증: URL 은 실제로 연 글이어야 하고, 근거 문장은 그 글 본문에 그대로 있어야 한다
              작성일은 모델이 아니라 페이지에서 읽는다
      → `classify()` (운영과 같은 규칙)

**약관** — 네이버 robots.txt 는 AI 학습·RAG 용 봇 접근을 금지한다고 적어 두었다. 이 스크립트는
"쓸 만한지"를 재는 일회성 실험이고 운영에 넣지 않는다. 결과 시트는 `eval/fixtures/`(커밋 금지)에만
쓰고, 페이지 사이에 쉬어 요청을 몰지 않는다.

출처는 모두 `blog.naver.com` 한 도메인이라 Signal 의 출처 수는 늘 1 이다.

사용법:
    uv run --with playwright python -m eval.naver_browse run --tag n1 --limit 10
    uv run --with playwright python -m eval.naver_browse run --tag n1 --ids 1,5,9 --headed
    uv run python -m eval.naver_browse show --tag n1
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import date
from pathlib import Path
from urllib.parse import quote

from dotenv import load_dotenv

AI_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(AI_ROOT / ".env")

from eval import mock_db  # noqa: E402
from eval.mock_round import change_summary  # noqa: E402
from src.investigation import InvestigationTarget  # noqa: E402
from src.investigation.classify import classify  # noqa: E402
from src.investigation.web import RATE_LIMIT_WAITS_SECONDS, _is_rate_limited  # noqa: E402
from src.investigation.web_research import (  # noqa: E402
    NOTHING_FOUND,
    Observation,
    ResearchResult,
    Source,
    _parse_line,
)

MODEL = "gemini-2.5-flash"
#: 도구 호출 상한. 검색 2~3번 + 글 3~4개면 충분하다 — 넘으면 가게 하나에 몇 분씩 걸린다.
MAX_STEPS = 8
LLM_TIMEOUT_SECONDS = 60.0
PAGE_PAUSE_SECONDS = 1.0  # 네이버에 요청을 몰지 않는다
STORE_PAUSE_SECONDS = 3.0  # Vertex 429
POST_TEXT_LIMIT = 6000  # 본문이 길면 모델 입력이 커진다. 가게 정보는 보통 앞쪽에 있다
SEARCH_RESULT_LIMIT = 10

BLOG_POST = re.compile(r"blog\.naver\.com/([A-Za-z0-9_-]+)/(\d+)")
ABSOLUTE_DATE = re.compile(r"(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.")
RELATIVE_DATE = re.compile(r"\d+\s*(?:분|시간)\s*전|방금")

COLUMNS = ["storeId", "그룹", "사업장명", "주소", "AI판정", "수정안", "신호", "관측", "단계", "실패", "초"]


def _force_utf8_output() -> None:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def _sheet(tag: str) -> Path:
    return mock_db.FIXTURES / f"naver_browse_{tag}.csv"


def _post_url(url: str) -> str | None:
    """블로그 글 주소를 iframe 없는 모바일 주소로 바꾼다. 블로그 글이 아니면 None."""
    m = BLOG_POST.search(url)
    return f"https://m.blog.naver.com/{m[1]}/{m[2]}" if m else None


def _parse_date(text: str) -> str:
    """'2026. 7. 22. 21:51' → '2026-07-22'. '3시간 전' 은 오늘. 모르면 빈 문자열."""
    if m := ABSOLUTE_DATE.search(text):
        return f"{m[1]}-{int(m[2]):02}-{int(m[3]):02}"
    if RELATIVE_DATE.search(text):
        return date.today().isoformat()
    return ""


def _compact(text: str) -> str:
    return re.sub(r"\s+", "", text)


@dataclass
class Post:
    url: str
    published: str
    text: str


@dataclass
class Session:
    """가게 하나를 조사하는 동안 브라우저가 본 것. 검증과 trace 에 쓴다."""

    searched: dict[str, list[dict[str, str]]] = field(default_factory=dict)  # 검색어 → 결과
    posts: dict[str, Post] = field(default_factory=dict)  # 모바일 URL → 글
    steps: list[str] = field(default_factory=list)


class NaverBlogBrowser:
    """Playwright 로 네이버 모바일 블로그 검색·글 읽기만 한다. 다른 사이트로는 가지 않는다."""

    def __init__(self, *, headed: bool = False) -> None:
        from playwright.sync_api import sync_playwright

        self._pw = sync_playwright().start()
        # 설치된 크롬을 쓴다 — playwright 브라우저를 따로 받지 않아도 된다.
        self._browser = self._pw.chromium.launch(channel="chrome", headless=not headed)
        self._page = self._browser.new_page(locale="ko-KR")

    def close(self) -> None:
        self._browser.close()
        self._pw.stop()

    def search(self, query: str) -> list[dict[str, str]]:
        self._page.goto(
            f"https://m.search.naver.com/search.naver?where=m_blog&query={quote(query)}",
            wait_until="domcontentloaded",
        )
        self._page.wait_for_timeout(PAGE_PAUSE_SECONDS * 1000)
        # 결과 하나는 글 링크 여러 개(썸네일·제목·본문 요약)를 품은 <li> 다. 링크마다 가장 가까운 <li> 의 글을 읽는다.
        items = self._page.evaluate(
            r"""() => {
                const seen = new Map();
                for (const a of document.querySelectorAll('a[href]')) {
                    if (!/blog\.naver\.com\/[A-Za-z0-9_-]+\/\d+/.test(a.href)) continue;
                    if (seen.has(a.href)) continue;
                    const li = a.closest('li');
                    seen.set(a.href, li ? li.innerText : a.innerText);
                }
                return [...seen].map(([url, text]) => ({url, text}));
            }"""
        )
        results, urls = [], set()
        for item in items:
            url = _post_url(item["url"])
            if url is None or url in urls:
                continue
            urls.add(url)
            text = re.sub(r"\s+", " ", item["text"]).strip()
            results.append({"url": url, "date": _parse_date(text), "summary": text[:300]})
            if len(results) >= SEARCH_RESULT_LIMIT:
                break
        return results

    def read(self, url: str) -> Post:
        self._page.goto(url, wait_until="domcontentloaded")
        self._page.wait_for_timeout(PAGE_PAUSE_SECONDS * 1000)
        body = self._page.query_selector(".se-main-container") or self._page.query_selector(".post_ct")
        text = body.inner_text() if body else self._page.inner_text("body")
        published = self._page.query_selector("p.blog_date")
        return Post(
            url=url,
            published=_parse_date(published.inner_text()) if published else "",
            text=re.sub(r"[ \t​]+", " ", re.sub(r"\n\s*\n+", "\n", text)).strip(),
        )


def build_prompt(target: InvestigationTarget) -> str:
    return f"""너는 가게 정보를 확인하는 조사원이다. 네이버 블로그를 검색하고 글을 읽어서 아래 가게의 **현재** 정보를 찾아라.

가게 이름: {target.name}
주소: {target.address or "(모름)"}
전화번호: {target.phone or "(모름)"}

도구:
- search_blog(query): 네이버 블로그 검색. 결과마다 URL·작성일·요약이 온다
- read_post(url): 검색 결과에 나온 글을 열어 본문을 읽는다

조사 방법:
- 상호명과 지역(구·동 이름)으로 검색해라. 결과가 이 가게가 아니면 검색어를 바꿔라
  (지점명 빼기, "식당"·"점" 같은 꼬리 빼기, 동 이름 넣기, 업종 넣기)
- 요약을 보고 이 가게 글로 보이는 것만 열어라. 최근 글을 먼저 열어라. 많아야 4개
- 주소·지점이 다르면 이름이 같아도 다른 가게다. 그 글의 값은 적지 마라

확인할 것 (항목 이름):
- status: 영업 상태. 영업 중이면 OPEN, 휴업이면 SUSPENDED, 폐업이면 CLOSED
- phone: 전화번호
- address: 도로명 주소
- name: 상호명

다 읽었으면 찾은 것마다 한 줄씩 아래 형식으로만 적어라. 다른 문장은 쓰지 마라.
항목 | 값 | 근거 문장 | URL

규칙:
- 근거 문장은 **read_post 로 읽은 본문에서 그대로 복사**한 짧은 구절이다(80자 이내). 고치거나 요약하지 마라
- URL 은 그 근거를 읽은 글의 URL 이다. 읽지 않은 글은 적지 마라
- 글마다 따로 적어라. 두 글에서 같은 값을 봤으면 두 줄이다
- 찾은 것이 없으면 "{NOTHING_FOUND}" 한 단어만 적어라
- 바뀌었는지 판단하지 마라. 본 것만 적어라"""


def _parse_answer(text: str, session: Session) -> list[dict[str, object]]:
    """최종 답을 관측으로 읽고 하나씩 검증한다. 검증을 통과한 것만 출처를 붙인다."""
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
        post = session.posts.get(url)
        if post is None:
            problem = "읽지 않은 글"
        elif _compact(observation.evidence) not in _compact(post.text):
            problem = "본문에 없는 근거"
        else:
            problem = ""
        out.append(
            {
                "observation": Observation(
                    field=observation.field,
                    value=observation.value,
                    evidence=observation.evidence,
                    observed_at=post.published if post else "",
                    sources=() if problem else (Source(domain="blog.naver.com", url=url),),
                ),
                "problem": problem,
            }
        )
    return out


class NaverBlogAgent:
    """Gemini 함수 호출로 브라우저를 몬다. 프레임워크 없이 함수 호출 루프를 직접 돈다."""

    def __init__(self, browser: NaverBlogBrowser, *, model: str = MODEL) -> None:
        from google import genai

        self._browser = browser
        self._model = model
        self._client = genai.Client(
            vertexai=True,
            project=os.environ["GOOGLE_CLOUD_PROJECT"],
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
        )

    def _generate(self, contents: list, config: object, session: Session) -> object:
        """429 면 `web.py` 와 같은 간격으로 기다렸다 다시 부른다. 그래도 나면 그 가게는 실패다."""
        waits = iter(RATE_LIMIT_WAITS_SECONDS)
        while True:
            try:
                return self._client.models.generate_content(model=self._model, contents=contents, config=config)
            except Exception as e:
                wait = next(waits, None) if _is_rate_limited(e) else None
                if wait is None:
                    raise
                session.steps.append(f"429 — {wait:.0f}초 대기")
                time.sleep(wait)

    def investigate(self, target: InvestigationTarget) -> tuple[list[dict[str, object]], Session]:
        from google.genai import types

        session = Session()
        browser = self._browser

        def search_blog(query: str) -> str:
            """네이버 블로그를 검색한다. 결과마다 URL, 작성일, 요약을 돌려준다."""
            results = browser.search(query)
            session.searched[query] = results
            session.steps.append(f"검색 {query!r} → {len(results)}건")
            if not results:
                return "검색 결과 없음"
            return "\n".join(f"{i + 1}. {r['url']} ({r['date'] or '날짜 모름'}) {r['summary']}" for i, r in enumerate(results))

        def read_post(url: str) -> str:
            """검색 결과에 나온 블로그 글을 열어 작성일과 본문을 돌려준다."""
            mobile = _post_url(url)
            known = {r["url"] for results in session.searched.values() for r in results}
            if mobile is None or mobile not in known:
                session.steps.append(f"거절 {url}")
                return "검색 결과에 나온 블로그 글만 열 수 있다"
            post = browser.read(mobile)
            session.posts[mobile] = post
            session.steps.append(f"읽기 {mobile} ({post.published or '날짜 모름'}, {len(post.text)}자)")
            return f"작성일: {post.published or '모름'}\n본문:\n{post.text[:POST_TEXT_LIMIT]}"

        tools = {"search_blog": search_blog, "read_post": read_post}
        http_options = types.HttpOptions(timeout=int(LLM_TIMEOUT_SECONDS * 1000))
        with_tools = types.GenerateContentConfig(
            tools=[search_blog, read_post],
            # SDK 의 자동 실행은 끄고 루프를 직접 돈다 — 호출 횟수를 세고 단계마다 trace 를 남기려고.
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            http_options=http_options,
        )
        contents: list[types.Content] = [types.Content(role="user", parts=[types.Part(text=build_prompt(target))])]
        calls = 0
        while True:
            config = with_tools if calls < MAX_STEPS else types.GenerateContentConfig(http_options=http_options)
            response = self._generate(contents, config, session)
            # thought_signature 를 잃지 않게 모델 응답을 그대로 이어 붙인다.
            contents.append(response.candidates[0].content)
            function_calls = response.function_calls or []
            if not function_calls or calls >= MAX_STEPS:
                break
            replies = []
            for call in function_calls:
                calls += 1
                fn = tools.get(call.name)
                result = fn(**(call.args or {})) if fn else f"모르는 도구: {call.name}"
                replies.append(types.Part.from_function_response(name=call.name, response={"result": result}))
            if calls >= MAX_STEPS:
                # 도구를 뺀 다음 호출에서 지금까지 읽은 것으로 답하게 한다.
                session.steps.append("상한 도달 — 정리 요청")
                replies.append(types.Part(text="검색은 그만하고 지금까지 읽은 글로 결과를 형식대로 적어라."))
            contents.append(types.Content(role="user", parts=replies))
        text = response.text or ""
        session.steps.append("답:\n" + text.strip())
        return _parse_answer(text, session), session


def _targets(base_tag: str, ids: list[str] | None, limit: int | None) -> list[tuple[dict, InvestigationTarget]]:
    """기본은 그라운딩 조사(`mock_round_<base_tag>`)에서 근거가 0건이었던 가게."""
    db = {str(r["storeId"]): r for r in mock_db.load()}
    if ids is None:
        with (mock_db.FIXTURES / f"mock_round_{base_tag}.csv").open(encoding="utf-8-sig", newline="") as f:
            ids = [
                r["storeId"]
                for r in csv.DictReader(f)
                if r["AI판정"] != "실패" and not any(o["sources"] for o in json.loads(r["관측"] or "[]"))
            ]
    picked = [db[i] for i in ids if i in db][:limit]
    return [(row, InvestigationTarget.model_validate(row)) for row in picked]


def _dump(checked: list[dict[str, object]]) -> str:
    return json.dumps(
        [asdict(c["observation"]) | {"problem": c["problem"]} for c in checked],
        ensure_ascii=False,
    )


def run(tag: str, *, base_tag: str, ids: list[str] | None, limit: int | None, headed: bool) -> int:
    sheet = _sheet(tag)
    kept = []
    if sheet.exists():
        with sheet.open(encoding="utf-8-sig", newline="") as f:
            kept = [r for r in csv.DictReader(f) if r["AI판정"] != "실패"]
    done = {r["storeId"] for r in kept}
    targets = [(row, t) for row, t in _targets(base_tag, ids, limit) if str(t.store_id) not in done]
    print(f"이미 한 것 {len(done)}곳 · 남은 것 {len(targets)}곳", flush=True)

    browser = NaverBlogBrowser(headed=headed)
    agent = NaverBlogAgent(browser)
    try:
        for i, (row, target) in enumerate(targets):
            out = {"storeId": str(target.store_id), "그룹": row["answer_group"], "사업장명": target.name,
                   "주소": target.address or ""}
            started = time.time()
            session = Session()
            try:
                checked, session = agent.investigate(target)
                found = classify(target, ResearchResult([c["observation"] for c in checked]))
                out |= {
                    "AI판정": found.classification.value,
                    "수정안": "; ".join(f"{k}={v}" for k, v in found.proposed_changes.items()),
                    "신호": change_summary(found),
                    "관측": _dump(checked),
                }
            except Exception as e:  # noqa: BLE001 - 한 건 실패로 표본 전체를 버리지 않는다
                out["AI판정"], out["실패"] = "실패", f"{type(e).__name__}: {e}"
            out["단계"] = "\n".join(session.steps)
            out["초"] = f"{time.time() - started:.0f}"
            kept.append(out)
            with sheet.open("w", encoding="utf-8-sig", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=COLUMNS)
                writer.writeheader()
                writer.writerows(kept)
            verified = sum(1 for o in json.loads(out.get("관측") or "[]") if not o["problem"])
            print(
                f"[{i + 1:2}/{len(targets)}] {out['그룹']} {target.name} → {out['AI판정']} "
                f"관측 {verified}건 {out.get('수정안', '')} ({out['초']}s, 단계 {len(session.steps)})",
                flush=True,
            )
            time.sleep(STORE_PAUSE_SECONDS)
    finally:
        browser.close()
    print(f"→ {sheet}")
    return show(tag)


def show(tag: str, sheet: Path | None = None) -> int:
    with (sheet or _sheet(tag)).open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    ok = [r for r in rows if r["AI판정"] != "실패"]
    print(f"\n[{tag}] {len(rows)}곳 (실패 {len(rows) - len(ok)}곳)")
    for group in ("폐업", "영업"):
        members = [r for r in ok if r["그룹"] == group]
        observed = [r for r in members if any(not o["problem"] for o in json.loads(r["관측"] or "[]"))]
        print(f"  {group} {len(members)}곳 · 검증된 관측이 있는 곳 {len(observed)} · 우선확인 "
              f"{sum(r['AI판정'] == 'PRIORITY_CHECK' for r in members)}")
    rejected = [o["problem"] for r in ok for o in json.loads(r["관측"] or "[]") if o["problem"]]
    if rejected:
        print(f"  검증에서 뺀 관측 {len(rejected)}건: " + ", ".join(sorted(set(rejected))))
    print("\n가게별 (관측: 항목=값 작성일)")
    for r in ok:
        obs = [o for o in json.loads(r["관측"] or "[]") if not o["problem"]]
        summary = ", ".join(f"{o['field']}={o['value']} {o['observed_at'] or '?'}" for o in obs) or "-"
        print(f"  {r['그룹']} {r['사업장명']}: {r['AI판정']} {r['수정안']} | {summary}")
    return 0


def main() -> int:
    _force_utf8_output()
    parser = argparse.ArgumentParser(description="네이버 블로그 브라우저 에이전트 실험")
    sub = parser.add_subparsers(dest="command", required=True)
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--tag", required=True)
    run_parser.add_argument("--base", default="v9", help="근거 0건 가게를 고를 그라운딩 시트 태그")
    run_parser.add_argument("--ids", help="storeId 목록(쉼표). 주면 --base 를 무시한다")
    run_parser.add_argument("--limit", type=int)
    run_parser.add_argument("--headed", action="store_true", help="브라우저 창을 띄운다")
    sub.add_parser("show").add_argument("--tag", required=True)
    args = parser.parse_args()
    if args.command == "run":
        ids = [i.strip() for i in args.ids.split(",")] if args.ids else None
        return run(args.tag, base_tag=args.base, ids=ids, limit=args.limit, headed=args.headed)
    return show(args.tag)


if __name__ == "__main__":
    raise SystemExit(main())
