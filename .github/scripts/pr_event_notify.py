#!/usr/bin/env python3
"""PR 이벤트가 생길 때 팀 디스코드 채널로 알린다. 멘션해야 할 사람을 멘션한다.

`notify-discord-team.yml` 이 호출한다. 판단 재료는 전부 GitHub 이 넘겨주는
이벤트 payload(`$GITHUB_EVENT_PATH`) 안에 있어서 API 를 따로 부르지 않는다.

다루는 이벤트
  pull_request        opened / ready_for_review / closed(머지된 것만) / review_requested
  pull_request_review submitted

**중복을 막는 지점** — PR 을 리뷰어까지 지정해서 올리면 GitHub 은 `opened` 와
`review_requested` 를 둘 다 쏜다. `opened` 에서 이미 리뷰어를 멘션했으므로,
PR 이 만들어진 지 FRESH_SECONDS 안쪽이면 `review_requested` 는 건너뛴다.
나중에 리뷰어를 바꾸거나 추가하면 그때는 PR 이 더 이상 새것이 아니라 정상 발송된다.

로컬 확인:
    GITHUB_EVENT_PATH=event.json GITHUB_EVENT_NAME=pull_request \
        python3 .github/scripts/pr_event_notify.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_members as dm  # noqa: E402

# 이 시간 안에 만들어진 PR 의 review_requested 는 opened 와 겹치므로 보내지 않는다
FRESH_SECONDS = 120

COLOR_NEW = 3447003       # 파랑
COLOR_MERGED = 5763719    # 초록
COLOR_REVIEW = 10181046   # 보라
COLOR_APPROVED = 5763719  # 초록
COLOR_CHANGES = 15548997  # 빨강
COLOR_WARN = 16753920     # 주황


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def reviewer_logins(pr: dict) -> list[str]:
    """봇은 뺀다. 팀(team) 리뷰어는 개인 멘션으로 바꿀 수 없어 다루지 않는다."""
    return [
        u["login"] for u in pr.get("requested_reviewers") or []
        if u.get("type") != "Bot"
    ]


def pr_embed(pr: dict, color: int, extra: list[dict] | None = None) -> dict:
    fields = [
        {"name": "작성자", "value": dm.name_of(pr["user"]["login"]), "inline": True},
        {"name": "브랜치", "value": pr["head"]["ref"], "inline": True},
    ]
    fields.extend(extra or [])
    if pr.get("additions") is not None:
        fields.append({
            "name": "변경",
            "value": f"+{pr['additions']} −{pr['deletions']} · {pr['changed_files']}개 파일",
            "inline": False,
        })
    return {
        "title": f"#{pr['number']} {pr['title']}",
        "url": pr["html_url"],
        "color": color,
        "fields": fields,
    }


def build(event_name: str, event: dict) -> dict | None:
    """보낼 payload. 보낼 것이 없으면 None."""
    pr = event.get("pull_request")
    if not pr:
        return None
    action = event.get("action")

    if event_name == "pull_request_review":
        return on_review(event, pr)

    if pr.get("draft"):
        return None  # 초안은 알리지 않는다

    if action in ("opened", "ready_for_review"):
        return on_opened(pr)
    if action == "review_requested":
        return on_review_requested(event, pr)
    if action == "closed" and pr.get("merged"):
        return {"content": "✅ develop 에 머지됐습니다",
                "embeds": [pr_embed(pr, COLOR_MERGED)]}
    return None


def on_opened(pr: dict) -> dict:
    reviewers = reviewer_logins(pr)
    if reviewers:
        content = ("🔵 새 PR 이 올라왔습니다 (→ develop)\n"
                   f"리뷰 부탁드립니다 — {dm.mentions(reviewers)}")
        color = COLOR_NEW
    else:
        # 리뷰어 없이 올라간 PR 은 24시간 리마인더가 돌 때까지 아무도 모른다. 바로 짚는다.
        content = ("🔵 새 PR 이 올라왔습니다 (→ develop)\n"
                   f"⚠️ 리뷰어가 지정되지 않았습니다 — {dm.mention(pr['user']['login'])} 지정해 주세요")
        color = COLOR_WARN
    return {"content": content, "embeds": [pr_embed(pr, color)]}


def on_review_requested(event: dict, pr: dict) -> dict | None:
    reviewer = (event.get("requested_reviewer") or {}).get("login")
    if not reviewer:
        return None  # 팀 단위 리뷰 요청. 멘션할 개인이 없다
    age = (datetime.now(timezone.utc) - parse_ts(pr["created_at"])).total_seconds()
    if age < FRESH_SECONDS:
        return None  # 방금 opened 가 이미 멘션했다
    return {
        "content": f"👀 리뷰어로 지정되셨습니다 — {dm.mention(reviewer)}",
        "embeds": [pr_embed(pr, COLOR_REVIEW)],
    }


def on_review(event: dict, pr: dict) -> dict | None:
    if event.get("action") != "submitted":
        return None
    review = event.get("review") or {}
    reviewer = (review.get("user") or {}).get("login")
    author = pr["user"]["login"]
    if reviewer == author:
        return None  # 자기 PR 에 자기가 단 코멘트는 알리지 않는다
    state = (review.get("state") or "").upper()
    headline, color = {
        "APPROVED": ("🟢 승인됐습니다", COLOR_APPROVED),
        "CHANGES_REQUESTED": ("🔴 변경 요청이 왔습니다", COLOR_CHANGES),
        "COMMENTED": ("💬 리뷰 코멘트가 달렸습니다", COLOR_REVIEW),
    }.get(state, (None, None))
    if not headline:
        return None

    # pull_request_review 는 GitHub 이 branches 필터를 지원하지 않아 main PR 리뷰도 들어온다.
    # 주간 멘토 리뷰 PR 이 그것이다. 되물음을 놓쳐 다음 주까지 답을 못 한 적이 있어,
    # 멘토 리뷰는 작성자 한 사람이 아니라 테크리더까지 같이 부른다.
    if pr["base"]["ref"] == "main":
        who = dm.mentions([author] + _table_tech_leads())
        line = ("멘토가 승인했습니다. 마감 전에 머지해 주세요"
                if state == "APPROVED"
                else "되물음이 있으면 추측하지 말고 해당 파트가 직접 답해 주세요")
        return {
            "content": f"🧑‍🏫 멘토 PR 에 리뷰가 달렸습니다\n{line} — {who}",
            "embeds": [pr_embed(pr, color, [
                {"name": "리뷰어", "value": reviewer, "inline": True},
            ])],
        }

    return {
        "content": f"{headline} — {dm.mention(author)}",
        "embeds": [pr_embed(pr, color, [
            {"name": "리뷰어", "value": dm.name_of(reviewer), "inline": True},
        ])],
    }


def _table_tech_leads() -> list[str]:
    return dm._table().get("tech_leads", [])


def post(webhook: str, payload: dict) -> None:
    # parse 를 users 로 좁힌다. @everyone·@here·역할 멘션이 본문에 섞여도 울리지 않는다.
    payload["allowed_mentions"] = {"parse": ["users"]}
    req = urllib.request.Request(
        webhook,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        print(f"디스코드 응답: {resp.status}")


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
