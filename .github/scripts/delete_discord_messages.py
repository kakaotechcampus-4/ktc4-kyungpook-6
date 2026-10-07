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


def _try_delete(url: str) -> tuple[bool, str]:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA},   # UA 를 빼면 Cloudflare 가 403 으로 막는다
        method="DELETE",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return True, f"지웠습니다 ({resp.status})"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False, "없는 메시지이거나 이 웹훅이 보낸 것이 아닙니다 (404)"
        return False, f"실패 {e.code} {e.reason}: {e.read().decode(errors='replace')[:200]}"


def delete(webhook: str, message_id: str) -> str:
    """스레드 → 채널 순으로 찾는다.

    메시지가 **어디 있는지는 ID 만 보고 알 수 없다.** 스레드 안의 것은 thread_id 를
    붙여야 찾히고, 채널에 바로 쓴 것은 붙이면 못 찾는다. 알림을 스레드로 옮기기 전에
    채널로 나간 것들이 남아 있어서, 둘 다 시도한다. 양쪽에서 404 면 진짜 없는 것이다.
    """
    base = f"{webhook.rstrip('/')}/messages/{message_id}"
    threaded = dw.with_thread(base)
    if threaded != base:
        ok, msg = _try_delete(threaded)
        if ok:
            return f"{msg} · 스레드"
        if "404" not in msg:
            return msg          # 404 가 아니면 채널로 재시도해도 같은 결과다
    ok, msg = _try_delete(base)
    return f"{msg} · 채널" if ok else msg


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
