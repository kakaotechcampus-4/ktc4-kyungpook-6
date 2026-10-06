#!/usr/bin/env python3
"""PR 이벤트가 생길 때 팀 디스코드 채널로 알린다. 멘션해야 할 사람을 멘션한다.

`notify-discord-team.yml` 이 호출한다. 판단 재료는 전부 GitHub 이 넘겨주는
이벤트 payload(`$GITHUB_EVENT_PATH`) 안에 있어서 API 를 따로 부르지 않는다.

다루는 이벤트
  pull_request                opened / ready_for_review / closed(머지된 것만) / review_requested
  pull_request_review         submitted
  issue_comment               created — PR 에 달린 일반 코멘트
  pull_request_review_comment created — **답글만** (in_reply_to_id 가 있는 것)

**메시지는 세 줄로 쓴다** — 무슨 일 / 어느 PR / 그래서 뭘 해야 하는지(+멘션+링크).
embed 를 쓰지 않는다. embed 안의 멘션은 울리지 않고, 여러 건이 쌓이면 멘션과 PR 이
따로 놀아서 누가 뭘 해야 하는지 안 보인다. remind_discord.py 와 모양을 맞춘다.

**멘토 리뷰는 시각이 정해져 있지 않다.** 언제 올지 모르고 재촉할 일도 아니라서,
cron 으로 "왔나?" 를 보지 않고 **멘토가 코멘트를 남기는 순간** 알린다.
8주차에 멘토 되물음을 다음 주까지 못 보고 넘긴 적이 있다.

코드 한 줄짜리 인라인 코멘트까지 전부 알리면 리뷰 한 번에 열 번이 울린다.
새로 달리는 인라인 코멘트는 리뷰 제출(`pull_request_review`)이 한 번에 묶어 알리므로,
`pull_request_review_comment` 는 **답글(되물음)만** 본다.

로컬 확인:
    GITHUB_EVENT_PATH=event.json GITHUB_EVENT_NAME=pull_request \
        python3 .github/scripts/pr_event_notify.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_members as dm  # noqa: E402

# PR 을 올릴 때 리뷰어를 같이 지정하면 GitHub 이 opened 와 review_requested 를 둘 다
# 보낸다. opened 가 이미 멘션했으므로 그 건은 건너뛴다.
#
# ⏱️ 짧게 잡아야 한다. 두 이벤트는 **1~2초 안에** 같이 오는데, 사람이 올린 뒤 손으로
#    리뷰어를 고르는 것도 1분 안쪽이다. 120초로 뒀다가 PR #70 에서 56초 뒤에 지정한
#    리뷰어 알림이 통째로 묻혔다.
FRESH_SECONDS = 20

# 웹훅은 기본적으로 **웹훅 자신의 이름**(운영진이 만들 때 붙인 이름)으로 글을 쓴다.
# 메시지마다 덮어쓸 수 있어서, 봇과 같은 이름·아바타로 맞춘다. 채널에서 보면
# 알림과 /예약 응답이 같은 "사랑이"로 보인다.
# 아바타를 바꾸면 해시도 바뀐다 — 그때 이 URL 을 같이 고칠 것.
WEBHOOK_NAME = "사랑이"
WEBHOOK_AVATAR = ("https://cdn.discordapp.com/avatars/1555129530186731520/"
                  "add6bacc3fd09363b755ef9dbe1bced6.webp?size=128")


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def reviewer_logins(pr: dict) -> list[str]:
    """봇은 뺀다. 팀(team) 리뷰어는 개인 멘션으로 바꿀 수 없어 다루지 않는다."""
    return [
        u["login"] for u in pr.get("requested_reviewers") or []
        if u.get("type") != "Bot"
    ]


def is_outsider(login: str | None) -> bool:
    """매핑에 없는 사람 = 팀원이 아니다. 멘토·운영진이 여기 걸린다."""
    return bool(login) and login not in dm._table().get("members", {})


def say(headline: str, subject: str, action: str = "", url: str = "") -> dict:
    """무슨 일 / 어느 PR / 그래서 뭘. 링크는 <> 로 감싸 미리보기 카드를 막는다."""
    lines = [headline, subject]
    tail = [t for t in (action, f"<{url}>" if url else "") if t]
    if tail:
        lines.append(" · ".join(tail))
    return {"content": "\n".join(lines)[:1900],
            "allowed_mentions": {"parse": ["users"]}}


def subject_of(pr: dict) -> str:
    """`**#12 제목** · 작성자 · +10 −2 · 3개 파일`"""
    parts = [f"**#{pr['number']} {pr['title']}**", dm.name_of(pr["user"]["login"])]
    if pr.get("additions") is not None:
        parts.append(f"+{pr['additions']} −{pr['deletions']} · {pr['changed_files']}개 파일")
    return " · ".join(parts)


def build(event_name: str, event: dict) -> dict | None:
    """보낼 payload. 보낼 것이 없으면 None."""
    if event_name == "issue_comment":
        return on_issue_comment(event)

    pr = event.get("pull_request")
    if not pr:
        return None
    action = event.get("action")

    if event_name == "pull_request_review":
        return on_review(event, pr)
    if event_name == "pull_request_review_comment":
        return on_reply(event, pr)

    if pr.get("draft"):
        return None  # 초안은 알리지 않는다

    if action in ("opened", "ready_for_review"):
        return on_opened(pr)
    if action == "review_requested":
        return on_review_requested(event, pr)
    if action == "closed" and pr.get("merged"):
        return say("✅ develop 에 머지됐습니다", subject_of(pr), url=pr["html_url"])
    return None


def on_opened(pr: dict) -> dict:
    reviewers = reviewer_logins(pr)
    if reviewers:
        action = f"리뷰 부탁드립니다 — {dm.mentions(reviewers)}"
    else:
        # 리뷰어 없이 올라간 PR 은 아무의 일도 아니다. 바로 짚는다.
        action = ("⚠️ 리뷰어가 없습니다. 같은 파트 팀원을 지정해 주세요 — "
                  + dm.mention(pr["user"]["login"]))
    return say("🔵 새 PR 이 올라왔습니다", subject_of(pr), action, pr["html_url"])


def on_review_requested(event: dict, pr: dict) -> dict | None:
    requested = event.get("requested_reviewer") or {}
    reviewer = requested.get("login")
    if not reviewer:
        return None  # 팀 단위 리뷰 요청. 멘션할 개인이 없다
    if requested.get("type") == "Bot" or reviewer == "Copilot":
        return None  # Copilot 같은 봇 리뷰어는 부를 사람이 없다
    age = (datetime.now(timezone.utc) - parse_ts(pr["created_at"])).total_seconds()
    if age < FRESH_SECONDS:
        return None  # 방금 opened 가 이미 멘션했다
    return say("👀 리뷰어로 지정되셨습니다", subject_of(pr),
               f"확인 부탁드립니다 — {dm.mention(reviewer)}", pr["html_url"])


def on_review(event: dict, pr: dict) -> dict | None:
    if event.get("action") != "submitted":
        return None
    review = event.get("review") or {}
    reviewer = (review.get("user") or {}).get("login")
    author = pr["user"]["login"]
    if reviewer == author:
        return None  # 자기 PR 에 자기가 단 코멘트는 알리지 않는다
    state = (review.get("state") or "").upper()

    # pull_request_review 는 GitHub 이 branches 필터를 지원하지 않아 main PR 리뷰도 들어온다.
    # 주간 멘토 리뷰 PR 이 그것이다. 되물음을 놓쳐 다음 주까지 답을 못 한 적이 있어,
    # 멘토 리뷰는 작성자 한 사람이 아니라 테크리더까지 같이 부른다.
    if pr["base"]["ref"] == "main":
        who = dm.mentions([author] + dm._table().get("tech_leads", []))
        action = ("멘토가 승인했습니다. 마감 전에 머지해 주세요" if state == "APPROVED"
                  else "되물음이 있으면 추측하지 말고 해당 파트가 직접 답해 주세요")
        return say(f"🧑‍🏫 멘토 리뷰가 달렸습니다 ({dm.name_of(reviewer)})",
                   subject_of(pr), f"{action} — {who}", pr["html_url"])

    headline, action = {
        "APPROVED": ("🟢 승인됐습니다", "머지하셔도 됩니다"),
        "CHANGES_REQUESTED": ("🔴 변경 요청이 왔습니다", "반영한 뒤 다시 리뷰를 요청해 주세요"),
        "COMMENTED": ("💬 리뷰 코멘트가 달렸습니다", "확인해 주세요"),
    }.get(state, (None, None))
    if not headline:
        return None
    return say(f"{headline} ({dm.name_of(reviewer)})", subject_of(pr),
               f"{action} — {dm.mention(author)}", pr["html_url"])


def comment_message(number: int, title: str, url: str, author: str | None,
                    commenter: str, body: str, outsider: bool) -> dict:
    """코멘트 알림 하나. 멘토면 테크리더까지 같이 부른다."""
    if outsider:
        who = dm.mentions([author] + dm._table().get("tech_leads", []))
        headline = f"🧑‍🏫 멘토 코멘트가 달렸습니다 ({commenter})"
        action = "추측해서 대신 답하지 말고 해당 파트가 직접 답해 주세요"
    else:
        who = dm.mention(author)
        headline = f"💬 코멘트가 달렸습니다 ({dm.name_of(commenter)})"
        action = "확인해 주세요"
    excerpt = " ".join((body or "").split())[:200]
    subject = f"**#{number} {title}**" + (f"\n> {excerpt}" if excerpt else "")
    return say(headline, subject, f"{action} — {who}", url)


def on_issue_comment(event: dict) -> dict | None:
    """PR 에 달린 일반 코멘트. 이슈 코멘트는 제외한다."""
    if event.get("action") != "created":
        return None
    issue = event.get("issue") or {}
    if "pull_request" not in issue:
        return None  # 진짜 이슈. PR 이 아니다
    c = event.get("comment") or {}
    commenter = (c.get("user") or {}).get("login")
    author = (issue.get("user") or {}).get("login")
    if commenter == author:
        return None  # 작성자가 자기 PR 에 쓴 말
    outsider = is_outsider(commenter)
    if not outsider and not author:
        return None
    return comment_message(issue["number"], issue.get("title", ""),
                           c.get("html_url", ""), author, commenter,
                           c.get("body", ""), outsider)


def on_reply(event: dict, pr: dict) -> dict | None:
    """코드 라인 코멘트의 **답글**. 멘토 되물음이 주로 여기로 온다.

    새로 다는 인라인 코멘트는 리뷰 제출이 한 번에 알리므로 여기서는 보지 않는다.
    그렇게 하지 않으면 리뷰 한 번에 열 번이 울린다.
    """
    if event.get("action") != "created":
        return None
    c = event.get("comment") or {}
    if not c.get("in_reply_to_id"):
        return None
    commenter = (c.get("user") or {}).get("login")
    author = pr["user"]["login"]
    if commenter == author:
        return None
    return comment_message(pr["number"], pr["title"], c.get("html_url", ""),
                           author, commenter, c.get("body", ""), is_outsider(commenter))


def post(webhook: str, payload: dict) -> None:
    payload = {**payload, "username": WEBHOOK_NAME, "avatar_url": WEBHOOK_AVATAR}
    req = urllib.request.Request(
        webhook,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            # 🚨 UA 를 빼면 디스코드(Cloudflare)가 403 으로 막는다.
            #    urllib 기본값 "Python-urllib/3.x" 가 차단 목록에 걸린다.
            "User-Agent": "ktc4-kyungpook-6-notifier (https://github.com/kakaotechcampus-4/ktc4-kyungpook-6)",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            print(f"디스코드 응답: {resp.status}")
    except urllib.error.HTTPError as e:
        # 웹훅 URL 은 절대 찍지 않는다. 응답 본문만 남겨야 원인을 안다.
        print(f"디스코드 전송 실패: {e.code} {e.reason}\n"
              f"{e.read().decode(errors='replace')[:400]}")
        raise


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="보내지 않고 payload 만 출력")
    args = ap.parse_args()

    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not os.path.exists(path):
        print("GITHUB_EVENT_PATH 가 필요합니다", file=sys.stderr)
        return 1
    with open(path, encoding="utf-8") as f:
        event = json.load(f)

    payload = build(os.environ.get("GITHUB_EVENT_NAME", "pull_request"), event)
    if not payload:
        print("알릴 것 없음")
        return 0

    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
    if not webhook:
        # 운영진 공용 웹훅으로 흘러가는 사고를 막기 위해 다른 웹훅을 대신 쓰지 않는다.
        print("::notice::DISCORD_WEBHOOK_TEAM 시크릿이 없어 알림을 건너뜁니다.")
        return 0
    post(webhook, payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
