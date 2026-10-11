"""깃허브 로그인 → 디스코드 멘션 변환. 알림 스크립트들이 같이 쓴다.

**매핑은 저장소에 두지 않는다.** 이 파일 하나로 "깃허브 계정 = 디스코드 계정 = 실명"이
연결되기 때문에, 저장소 시크릿 `DISCORD_MEMBERS` 에 JSON 문자열로 넣는다.
형식은 `.github/discord-members.example.json` 을 보면 된다.

읽는 순서
  1. 환경변수 `DISCORD_MEMBERS` (워크플로가 시크릿에서 넣어준다) — 운영 경로
  2. 환경변수 `DISCORD_MEMBERS_PATH` 가 가리키는 파일 — 로컬에서 손으로 돌려볼 때
  3. 둘 다 없으면 빈 매핑. **알림은 그대로 나가고 멘션만 안 붙는다.**

멘션은 **content 에 넣어야 울린다.** embed 안의 `<@id>` 는 링크로 보이기만 하고
알림이 가지 않는다. 이 모듈을 쓰는 쪽은 멘션을 반드시 content 로 보낼 것.
"""

from __future__ import annotations

import json
import os
from functools import lru_cache

EMPTY = {"members": {}, "tech_leads": []}


@lru_cache(maxsize=1)
def _table() -> dict:
    raw = os.environ.get("DISCORD_MEMBERS")
    source = "시크릿 DISCORD_MEMBERS"
    if not raw:
        path = os.environ.get("DISCORD_MEMBERS_PATH")
        if not path:
            print("::notice::DISCORD_MEMBERS 가 없어 멘션 없이 보냅니다.")
            return EMPTY
        source = path
        try:
            with open(path, encoding="utf-8") as f:
                raw = f.read()
        except OSError as e:
            print(f"::warning::매핑 파일을 읽지 못해 멘션 없이 보냅니다 ({e})")
            return EMPTY
    try:
        table = json.loads(raw)
    except json.JSONDecodeError as e:
        # 매핑이 깨져도 알림 자체는 나가야 한다. 멘션만 포기한다.
        # 시크릿 값은 절대 출력하지 않는다 — 줄/열 위치만 알려도 고치는 데 충분하다.
        print(f"::warning::{source} 의 JSON 이 깨져 멘션 없이 보냅니다 "
              f"(line {e.lineno} col {e.colno})")
        return EMPTY
    if not isinstance(table, dict) or "members" not in table:
        print(f"::warning::{source} 에 members 키가 없어 멘션 없이 보냅니다")
        return EMPTY
    return table


def mention(login: str | None) -> str:
    """`<@123>` 또는, 매핑에 없으면 멘션하지 않는 평문 `` `login` ``."""
    if not login:
        return ""
    entry = _table().get("members", {}).get(login)
    if entry and entry.get("discord"):
        return f"<@{entry['discord']}>"
    return f"`{login}`"


def mentions(logins) -> str:
    """중복과 빈 값을 걸러 공백으로 잇는다. 순서는 받은 그대로 유지한다."""
    seen, out = set(), []
    for login in logins or []:
        if not login or login in seen:
            continue
        seen.add(login)
        out.append(mention(login))
    return " ".join(out)


def tech_leads() -> str:
    return mentions(_table().get("tech_leads", []))


def name_of(login: str | None) -> str:
    """사람 이름(없으면 로그인 그대로). 멘션이 아니라 본문 표시용."""
    entry = _table().get("members", {}).get(login or "")
    return entry["name"] if entry else (login or "?")
