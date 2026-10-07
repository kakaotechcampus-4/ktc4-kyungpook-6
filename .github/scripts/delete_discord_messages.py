#!/usr/bin/env python3
"""웹훅이 보낸 메시지를 지운다.

웹훅은 **자기가 보낸 메시지**를 Discord 권한 없이 지울 수 있다
(`DELETE /webhooks/{id}/{token}/messages/{message_id}`). 채널 「메시지 관리」
권한이 없어도 된다 — 우리 팀 계정에는 그 권한이 없다.

시험 삼아 보낸 알림을 치울 때 쓴다. 메시지 ID 는 디스코드에서
개발자 모드를 켜고(사용자 설정 → 고급) 메시지 우클릭 → "메시지 ID 복사하기".

    DISCORD_WEBHOOK_TEAM=... python3 .github/scripts/delete_discord_messages.py 123 456

⚠️ 지운 메시지는 되돌릴 수 없다. 남의 메시지는 애초에 지워지지 않는다
   (웹훅 자기 것만 가능 — 다른 것을 넣으면 404 가 나고 그대로 넘어간다).
"""

from __future__ import annotations

import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_webhook as dw  # noqa: E402

UA = "ktc4-kyungpook-6-notifier (https://github.com/kakaotechcampus-4/ktc4-kyungpook-6)"


def delete(webhook: str, message_id: str) -> str:
    # 스레드 안의 메시지는 thread_id 없이는 찾지 못한다 — 404 가 나고, 그러면
    # "이 웹훅이 보낸 게 아니다"로 잘못 읽게 된다. 보낼 때와 같은 값을 붙인다.
    req = urllib.request.Request(
        dw.with_thread(f"{webhook.rstrip('/')}/messages/{message_id}"),
        headers={"User-Agent": UA},   # UA 를 빼면 Cloudflare 가 403 으로 막는다
        method="DELETE",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return f"지웠습니다 ({resp.status})"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return "없는 메시지이거나 이 웹훅이 보낸 것이 아닙니다 (404)"
        return f"실패 {e.code} {e.reason}: {e.read().decode(errors='replace')[:200]}"


def main() -> int:
    ids = [a for a in sys.argv[1:] if a.strip()]
    if not ids:
        print("지울 메시지 ID 를 인자로 주세요", file=sys.stderr)
        return 1
    webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
    if not webhook:
        print("DISCORD_WEBHOOK_TEAM 이 없습니다", file=sys.stderr)
        return 1
    bad = 0
    for mid in ids:
        if not mid.isdigit():
            print(f"  {mid}: 숫자가 아닙니다. 건너뜁니다")
            continue
        result = delete(webhook, mid)
        print(f"  {mid}: {result}")
        bad += "실패" in result
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
