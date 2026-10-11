"""웹훅 URL 에 스레드 지정을 붙인다. 알림 스크립트 둘이 같이 쓴다.

PR 알림이 주당 10건 넘게 쌓이면서 팀 채널의 다른 대화가 묻혔다. 그래서 **채널이 아니라
그 채널 안의 스레드 하나로** 보낸다. 환경변수 `DISCORD_THREAD_ID` 가 있으면 거기로 가고,
없으면 지금까지처럼 채널로 간다. **스레드를 안 쓰기로 해도 코드를 되돌릴 필요가 없다.**

스레드 ID 얻는 법 — 디스코드에서 스레드 우클릭 → `링크 복사`. 맨 뒤 숫자가 ID 다.
`.../channels/<서버>/<스레드>` 의 마지막 칸.

알아 둘 것
  - **보관(archive)된 스레드는 자동으로 풀린다.** `thread_id` 로 보내면 디스코드가
    알아서 되살리므로, 며칠 조용하다 알림이 오는 경우에도 죽지 않는다.
  - 멘션은 스레드 안에서도 울린다. 멘션당한 사람은 그 스레드에 자동으로 들어온다.
  - 스레드가 지워졌거나 잠겨 있으면 디스코드가 10003/50083 으로 거절한다. 이때는
    **알림이 통째로 사라진다** — 그래서 보내는 쪽에서 채널로 한 번 더 시도한다.
"""

from __future__ import annotations

import os
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

THREAD_ENV = "DISCORD_THREAD_ID"


def thread_id() -> str:
    """설정된 스레드 ID. 없으면 빈 문자열."""
    return os.environ.get(THREAD_ENV, "").strip()


def with_thread(webhook: str, tid: str | None = None) -> str:
    """웹훅 URL 에 `?thread_id=` 를 붙인다. ID 가 없으면 URL 을 그대로 돌려준다.

    웹훅 URL 에 이미 쿼리가 붙어 있을 수 있어서(`?wait=true` 등) 문자열을 이어 붙이지
    않고 파싱해서 넣는다.
    """
    tid = thread_id() if tid is None else tid.strip()
    if not tid:
        return webhook
    parts = urlparse(webhook)
    query = dict(parse_qsl(parts.query))
    query["thread_id"] = tid
    return urlunparse(parts._replace(query=urlencode(query)))
