#!/usr/bin/env python3
"""PR 이벤트가 생길 때 팀 디스코드로 알린다. 멘션해야 할 사람을 멘션한다.

`notify-discord-team.yml` 이 호출한다. 판단 재료는 전부 GitHub 이 넘겨주는
이벤트 payload(`$GITHUB_EVENT_PATH`) 안에 있어서 API 를 따로 부르지 않는다.
보내는 곳은 채널이 아니라 그 안의 스레드다 (`discord_webhook.py`).

다루는 이벤트
  pull_request                opened / ready_for_review / reopened / closed(머지된 것만)
                              / review_requested
  pull_request_review         submitted
  issue_comment               created — PR 에 달린 일반 코멘트
  pull_request_review_comment created — **답글만** (in_reply_to_id 가 있는 것)

**부를 사람과 할 일은 content, 무슨 일·어느 PR 은 embed.** 두 자리를 나눈 이유가 각각 있다.

- 🚨 **멘션은 content 에만.** embed 안의 `<@id>` 는 링크로 보이기만 하고 알림이 가지 않는다.
  `--dry-run` 으로는 멀쩡해 보여서 실제로 보내 보고야 알았다.
- 📏 **embed 를 쓰는 건 스레드 때문이다.** 같은 웹훅이 연달아 보내면 디스코드가 메시지를
  묶어서 이름·시각을 맨 위 한 번만 보여준다. 채널에서는 GitHub 링크 미리보기가 칸막이
  노릇을 했는데, 미리보기를 끄면서(`<>`) 그것도 없어졌다. embed 왼쪽 색상 세로바가
  그 자리를 대신하고, 색으로 종류까지 구분된다.

`remind_discord.py` 도 같은 모양이다 — 그쪽은 건마다 메시지 하나로 쪼개서 맞춘다.

🔇 **멘토·운영진이 남긴 것은 이 채널로 알리지 않는다.** 운영진 워크플로
(`notify-discord.yml`)가 `#pr-alert-경북대` 로 이미 보내고 있어서, 여기까지 울리면
같은 일이 두 번 울린다. 팀원이 아닌 사람(`DISCORD_MEMBERS` 매핑에 없는 사람)이
남긴 리뷰·코멘트는 전부 건너뛴다. main PR 리뷰도 마찬가지다 —
`pull_request_review` 는 GitHub 이 branches 필터를 지원하지 않아 여기로 들어오지만,
그건 주간 멘토 리뷰 PR 이라 걸러 낸다.

코드 한 줄짜리 인라인 코멘트까지 전부 알리면 리뷰 한 번에 열 번이 울린다.
새로 달리는 인라인 코멘트는 리뷰 제출(`pull_request_review`)이 한 번에 묶어 알리므로,
`pull_request_review_comment` 는 **답글만** 본다.

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
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_members as dm  # noqa: E402
import discord_webhook as dw  # noqa: E402

# PR 을 올릴 때 리뷰어를 같이 지정하면(`gh pr create --reviewer`) GitHub 이 opened 와
# review_requested 를 **둘 다** 보낸다. 그 건은 opened 가 이미 멘션했으므로 건너뛴다.
#
# ⏱️ **러너 시계로 재지 않는다.** `datetime.now()` 로 재면 큐 대기·체크아웃 시간이 age 에
#    섞여서, 같은 상황인데 러너가 빠르면 묻히고 느리면 나간다. 대신 GitHub 이 서버에서
#    찍은 두 값의 차이를 본다 — 리뷰 요청이 들어오면 `pull_request.updated_at` 이
#    갱신되므로 `updated_at - created_at` 이 **생성과 리뷰어 지정 사이의 실제 간격**이다.
#
# 🚨 창을 세 번 틀렸다. 전부 **알림이 안 가는 쪽**으로 틀렸다.
#    120초 → PR #70 에서 56초 뒤 지정이 묻혔다 → 20초로 줄임
#    20초  → PR #84·#85 에서 9~10초 뒤 지정이 묻혔다 → 러너 시계를 버리고 2초로 줄임
#
#    원자적 생성은 두 이벤트가 1~2초 안에 온다. 사람이나 CLI 가 나중에 넣는 건 그보다 길다.
#    경계에서 틀릴 때는 **보내는 쪽으로** 틀리게 둔다 — 중복 멘션 한 번은 거슬릴 뿐이지만,
#    안 가면 리뷰어가 자기가 지정된 걸 모르고 리뷰가 멈춘다.
ATOMIC_WINDOW_SECONDS = 2

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


# embed 왼쪽 세로바 색. 종류를 색으로 구분해, 스크롤하며 훑어도 무슨 알림인지 보이게 한다.
COLOR_NEW = 0x5865F2       # 파랑 — 새 PR
COLOR_REVIEW = 0xFEE75C    # 노랑 — 리뷰어 지정
COLOR_GOOD = 0x57F287      # 초록 — 승인 · 머지
COLOR_CHANGES = 0xED4245   # 빨강 — 변경 요청
COLOR_COMMENT = 0x99AAB5   # 회색 — 코멘트


def say(headline: str, subject: str, action: str = "", url: str = "",
        *, mention: str = "", color: int = COLOR_COMMENT) -> dict:
    """부를 사람과 할 일은 content 에, 무슨 일·어느 PR 은 embed 에.

    🚨 **멘션은 반드시 content 에 둔다.** embed 안의 `<@id>` 는 링크로 보이기만 하고
       알림이 가지 않는다 — `--dry-run` 으로는 멀쩡해 보여서 실제로 보내 보고야 알았다.

    **왜 embed 를 쓰나** — 알림을 채널이 아니라 스레드(「📋 PR 현황판」)로 보내면서
    메시지 구분이 사라졌다. 같은 웹훅이 연달아 보내면 디스코드가 메시지를 묶어서 이름·시각을
    맨 위 한 번만 보여주기 때문에, 여러 건이 한 덩어리로 읽힌다. 채널에서는 GitHub 링크
    미리보기 카드가 칸막이 노릇을 했는데, 미리보기를 끄면서(`<>`) 그것도 없어졌다.
    embed 는 왼쪽에 색상 세로바가 붙어 건마다 경계가 생긴다.

    제목에 `url` 을 걸어 링크를 따로 적지 않는다 — 미리보기 카드가 다시 붙지 않는다.
    """
    embed: dict = {"title": headline[:256], "description": subject[:4000], "color": color}
    if url:
        embed["url"] = url
    content = " ".join(t for t in (mention, action) if t)
    return {"content": content[:1900], "embeds": [embed],
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

    if action in ("opened", "ready_for_review", "reopened"):
        # reopened 를 넣은 이유 — GitHub 이 이벤트를 흘려 워크플로가 하나도 안 돈 PR 이 있었다
        # (#76, 체크 0개). 닫았다 다시 열면 CI 는 되살아나는데, 그때 알림까지 와야 한다.
        return on_opened(pr)
    if action == "review_requested":
        return on_review_requested(event, pr)
    if action == "closed" and pr.get("merged"):
        return say("✅ develop 에 머지됐습니다", subject_of(pr), url=pr["html_url"],
                   color=COLOR_GOOD)
    return None


def on_opened(pr: dict) -> dict:
    reviewers = reviewer_logins(pr)
    if reviewers:
        action, who = "리뷰 부탁드립니다", dm.mentions(reviewers)
    else:
        # 리뷰어 없이 올라간 PR 은 아무의 일도 아니다. 바로 짚는다.
        action = "⚠️ 리뷰어가 없습니다. 같은 파트 팀원을 지정해 주세요"
        who = dm.mention(pr["user"]["login"])
    return say("🔵 새 PR 이 올라왔습니다", subject_of(pr), action, pr["html_url"],
               mention=who, color=COLOR_NEW)


def requested_with_pr(pr: dict) -> bool:
    """리뷰어가 **PR 생성과 한 묶음으로** 지정됐는가.

    그렇다면 `opened` 가 이미 그 사람을 멘션했으니 다시 보내지 않는다.

    판단은 **GitHub 이 서버에서 찍은 두 값**으로만 한다. 러너 시계(`datetime.now()`)를
    쓰면 큐 대기 시간이 섞여 판정이 흔들린다 — 그래서 #84·#85 가 묻혔다.

    `updated_at` 을 읽을 수 없으면 **보내는 쪽**을 고른다. 모를 때 묻어 버리는 것이
    지금까지 세 번 다 사고가 난 방향이다.
    """
    created, updated = pr.get("created_at"), pr.get("updated_at")
    if not created or not updated:
        return False
    try:
        gap = (parse_ts(updated) - parse_ts(created)).total_seconds()
    except ValueError:
        return False
    return 0 <= gap <= ATOMIC_WINDOW_SECONDS


def on_review_requested(event: dict, pr: dict) -> dict | None:
    requested = event.get("requested_reviewer") or {}
    reviewer = requested.get("login")
    if not reviewer:
        return None  # 팀 단위 리뷰 요청. 멘션할 개인이 없다
    if requested.get("type") == "Bot" or reviewer == "Copilot":
        return None  # Copilot 같은 봇 리뷰어는 부를 사람이 없다
    if requested_with_pr(pr):
        return None  # opened 가 이 리뷰어를 이미 멘션했다
    return say("👀 리뷰어로 지정되셨습니다", subject_of(pr), "확인 부탁드립니다",
               pr["html_url"], mention=dm.mention(reviewer), color=COLOR_REVIEW)


def on_review(event: dict, pr: dict) -> dict | None:
    if event.get("action") != "submitted":
        return None
    review = event.get("review") or {}
    reviewer = (review.get("user") or {}).get("login")
    author = pr["user"]["login"]
    if reviewer == author:
        return None  # 자기 PR 에 자기가 단 코멘트는 알리지 않는다
    state = (review.get("state") or "").upper()

    # 🔇 **멘토 쪽은 이 채널로 알리지 않는다.** 운영진 워크플로(notify-discord.yml)가
    #    `#pr-alert-경북대` 로 이미 보내고 있어서, 여기까지 울리면 같은 일이 두 번 울린다.
    #
    #    pull_request_review 는 GitHub 이 branches 필터를 지원하지 않아 main PR 리뷰도 들어온다.
    #    주간 멘토 리뷰 PR 이 그것이라 여기서 걸러 낸다.
    if pr["base"]["ref"] == "main":
        return None
    # 팀원이 아닌 사람(매핑에 없는 사람)이 develop PR 에 남긴 리뷰도 같은 이유로 조용히 둔다.
    if is_outsider(reviewer):
        return None

    headline, action, color = {
        "APPROVED": ("🟢 승인됐습니다", "머지하셔도 됩니다", COLOR_GOOD),
        "CHANGES_REQUESTED": ("🔴 변경 요청이 왔습니다", "반영한 뒤 다시 리뷰를 요청해 주세요",
                              COLOR_CHANGES),
        "COMMENTED": ("💬 리뷰 코멘트가 달렸습니다", "확인해 주세요", COLOR_COMMENT),
    }.get(state, (None, None, None))
    if not headline:
        return None
    return say(f"{headline} ({dm.name_of(reviewer)})", subject_of(pr), action,
               pr["html_url"], mention=dm.mention(author), color=color)


def comment_message(number: int, title: str, url: str, author: str | None,
                    commenter: str, body: str) -> dict:
    """팀원이 남긴 코멘트 알림. 멘토·운영진 것은 여기까지 오지 않는다(위에서 걸러진다)."""
    excerpt = " ".join((body or "").split())[:200]
    subject = f"**#{number} {title}**" + (f"\n> {excerpt}" if excerpt else "")
    return say(f"💬 코멘트가 달렸습니다 ({dm.name_of(commenter)})", subject, "확인해 주세요",
               url, mention=dm.mention(author), color=COLOR_COMMENT)


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
    # 🔇 멘토·운영진 코멘트는 알리지 않는다 — 운영진 채널이 담당한다.
    if is_outsider(commenter) or not author:
        return None
    return comment_message(issue["number"], issue.get("title", ""),
                           c.get("html_url", ""), author, commenter, c.get("body", ""))


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
    if is_outsider(commenter):
        return None  # 🔇 멘토 되물음도 운영진 채널이 담당한다
    return comment_message(pr["number"], pr["title"], c.get("html_url", ""),
                           author, commenter, c.get("body", ""))


def _send(url: str, payload: dict) -> None:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            # 🚨 UA 를 빼면 디스코드(Cloudflare)가 403 으로 막는다.
            #    urllib 기본값 "Python-urllib/3.x" 가 차단 목록에 걸린다.
            "User-Agent": "ktc4-kyungpook-6-notifier (https://github.com/kakaotechcampus-4/ktc4-kyungpook-6)",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        print(f"디스코드 응답: {resp.status}")


def post(webhook: str, payload: dict) -> None:
    payload = {**payload, "username": WEBHOOK_NAME, "avatar_url": WEBHOOK_AVATAR}
    url = dw.with_thread(webhook)
    try:
        _send(url, payload)
    except urllib.error.HTTPError as e:
        # 웹훅 URL 은 절대 찍지 않는다. 응답 본문만 남겨야 원인을 안다.
        print(f"디스코드 전송 실패: {e.code} {e.reason}\n"
              f"{e.read().decode(errors='replace')[:400]}")
        # 스레드가 지워졌거나 잠겼으면 **알림이 통째로 사라진다.** 묻히는 것보다 나쁘다.
        # 채널로 한 번 더 보내고, 스레드를 손봐야 한다는 걸 로그에 남긴다.
        if url == webhook:
            raise
        print(f"::warning::{dw.THREAD_ENV} 로 보내지 못해 채널로 보냅니다. "
              "스레드가 지워졌거나 잠긴 건 아닌지 확인하세요.")
        _send(webhook, payload)


def sample() -> dict:
    """점검용 한 건. 실제 알림과 **같은 say() 를 거쳐** 만든다 — 모양이 어긋나면 의미가 없다.

    멘션은 버튼을 누른 사람만. 이 저장소는 공개라 디스코드 ID 를 워크플로 입력으로 받지 않고
    깃허브 로그인(공개 정보)만 받아 시크릿 매핑에서 찾는다.
    """
    actor = os.environ.get("GITHUB_ACTOR", "").strip()
    return say(
        "🧪 점검용 알림입니다",
        "**#0 이 줄이 왼쪽 색 막대와 함께 보이면 구분이 되는 것입니다**\n"
        "눌러주신 분만 멘션했습니다 — 다른 분께는 알림이 가지 않습니다.",
        "확인만 해주세요",
        mention=dm.mention(actor) if actor else "",
        color=COLOR_NEW,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="보내지 않고 payload 만 출력")
    ap.add_argument("--test", action="store_true",
                    help="PR 이벤트 없이 점검용 알림 한 건만 보낸다. 누른 사람만 멘션한다")
    args = ap.parse_args()

    if args.test:
        payload = sample()
        if args.dry_run:
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            return 0
        webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
        if not webhook:
            print("::notice::DISCORD_WEBHOOK_TEAM 시크릿이 없어 건너뜁니다.")
            return 0
        tid = dw.thread_id()
        print(f"보내는 곳: {'스레드 ' + tid if tid else '채널 (DISCORD_THREAD_ID 비어 있음)'}")
        post(webhook, payload)
        return 0

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
