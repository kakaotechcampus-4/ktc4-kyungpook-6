#!/usr/bin/env python3
"""매 시간 돌면서 사람이 손써야 하는 PR 만 디스코드에 알린다.

봇이 아니라 GitHub Actions 의 schedule(cron) + 웹훅이다. 알림만 보내면 되니
24시간 켜둘 서버가 필요 없다. 디코에서 명령어로 조회하고 싶어지면 그때 봇을 얹는다.

**같은 PR 을 매시간 다시 알리지 않는 방법** — 조건을 "임계값을 넘었다"가 아니라
"이번 한 시간 안에 임계값을 넘었다"로 좁힌다. 예를 들어 리뷰 없이 24시간이 지난 PR 이
아니라, 열린 지 24시간 이상 25시간 미만인 PR 만 고른다. 매시간 도니까 각 PR 은 그 구간을
정확히 한 번만 지나간다. 상태를 저장할 곳이 없어도 중복이 안 생긴다.

마감 알림과 충돌 점검은 시각이 고정이라(수 18:00 / 토 10:00 / 일 23:59, 매일 10:00)
구간 계산이 필요 없다.

멘션 대상은 `.github/discord-members.json` 이 정한다. 사람이 바뀌면 그 파일만 고친다.

로컬 확인:
    GITHUB_TOKEN=$(gh auth token) GITHUB_REPOSITORY=kakaotechcampus-4/ktc4-kyungpook-6 \
        python3 .github/scripts/remind_discord.py --dry-run
    ... --dry-run --now '2026-10-07T17:30+09:00'   # 수요일 마감 1시간 전인 것처럼 굴려본다
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_members as dm  # noqa: E402

KST = timezone(timedelta(hours=9))
API = "https://api.github.com"

# 리뷰가 하나도 없이 이만큼 지나면 알린다 (시간)
NO_REVIEW_HOURS = 24
# 승인됐는데 머지되지 않고 이만큼 지나면 알린다 (시간)
APPROVED_UNMERGED_HOURS = 6
# 충돌난 PR 을 점검하는 시각 (KST). 충돌은 "언제 깨졌는지" 타임스탬프가 없어서
# 구간 계산을 못 한다. 그래서 하루 한 번 고정 시각에만 본다.
CONFLICT_CHECK_HOUR = 10

# (요일, 1시간 전 시각, 마감 설명). 월=0.  `_local/주차별_PR/README.md` 의 일정표
DEADLINES = [
    (2, 17, "오늘 18:00 1차 PR 마감"),
    (5, 9, "오늘 10:00 2차 재리뷰 요청 마감"),
    (6, 23, "오늘 23:59 main 머지 마감"),
]

COLOR_WARN = 16753920   # 주황
COLOR_INFO = 3447003    # 파랑
COLOR_DEADLINE = 15548997  # 빨강


def gh(path: str, token: str) -> object:
    req = urllib.request.Request(
        f"{API}{path}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "ktc4-kyungpook-6-reminder",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def in_band(hours_elapsed: float, threshold: int) -> bool:
    """임계값을 이번 한 시간 안에 넘었는가. 매시간 실행이면 PR 하나당 한 번만 참이다."""
    return threshold <= hours_elapsed < threshold + 1


def latest_review_state(reviews: list[dict]) -> str | None:
    """사람별 마지막 리뷰만 본다. COMMENTED 는 승인·변경요청을 덮지 않는다."""
    by_user: dict[str, dict] = {}
    for r in reviews:
        if r.get("state") == "COMMENTED":
            continue
        login = (r.get("user") or {}).get("login")
        if login:
            by_user[login] = r
    states = {r["state"] for r in by_user.values()}
    if "CHANGES_REQUESTED" in states:
        return "CHANGES_REQUESTED"
    if "APPROVED" in states:
        return "APPROVED"
    return None


def approved_at(reviews: list[dict]) -> datetime | None:
    stamps = [parse_ts(r["submitted_at"]) for r in reviews if r.get("state") == "APPROVED"]
    return max(stamps) if stamps else None


def people(pr: dict) -> list[str]:
    """작성자 + 지정된 리뷰어. 봇은 뺀다."""
    out = [pr["user"]["login"]]
    out += [u["login"] for u in pr.get("requested_reviewers") or [] if u.get("type") != "Bot"]
    return out


def reviewers(pr: dict) -> list[str]:
    return [u["login"] for u in pr.get("requested_reviewers") or [] if u.get("type") != "Bot"]


def build_findings(repo: str, token: str, now: datetime) -> list[dict]:
    """알릴 것만 모은다. 아무것도 없으면 빈 리스트."""
    findings: list[dict] = []
    k = now.astimezone(KST)
    deadline_label = next(
        (label for wd, hour, label in DEADLINES if (k.weekday(), k.hour) == (wd, hour)), None
    )

    pulls = gh(f"/repos/{repo}/pulls?state=open&per_page=100", token)

    main_pr = None
    for pr in pulls:
        if pr.get("draft"):
            continue
        base = pr["base"]["ref"]
        if base == "main":
            main_pr = pr   # 멘토 리뷰 PR. 아래 리뷰 독촉 대상에서는 제외된다
            continue
        if base != "develop":
            continue

        reviews = gh(f"/repos/{repo}/pulls/{pr['number']}/reviews?per_page=100", token)
        opened_hours = (now - parse_ts(pr["created_at"])).total_seconds() / 3600
        state = latest_review_state(reviews)

        if not reviews and in_band(opened_hours, NO_REVIEW_HOURS):
            findings.append({
                "color": COLOR_WARN,
                "headline": "🕐 리뷰 없이 하루가 지났습니다",
                "pr": pr,
                "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건",
                "mention": dm.mentions(reviewers(pr) or [pr["user"]["login"]]),
            })

        # 마감 1시간 전에 아직 리뷰가 하나도 없는 PR — 작성자와 리뷰어를 같이 부른다.
        # 멘토 리뷰 PR(base=main)은 위에서 이미 걸러져 여기 오지 않는다.
        if deadline_label and not reviews:
            findings.append({
                "color": COLOR_DEADLINE,
                "headline": f"⏰ 1시간 뒤 {deadline_label} — 아직 리뷰가 없습니다",
                "pr": pr,
                "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건",
                "mention": dm.mentions(people(pr)),
            })

        if state == "APPROVED":
            since = approved_at(reviews)
            if since and in_band((now - since).total_seconds() / 3600, APPROVED_UNMERGED_HOURS):
                findings.append({
                    "color": COLOR_INFO,
                    "headline": "🟢 승인됐는데 아직 머지되지 않았습니다",
                    "pr": pr,
                    "detail": f"승인 후 {APPROVED_UNMERGED_HOURS}시간 경과",
                    "mention": dm.mention(pr["user"]["login"]),
                })

        if k.hour == CONFLICT_CHECK_HOUR:
            findings.extend(conflict_finding(repo, token, pr))

    findings.extend(weekly_deadlines(now, main_pr, deadline_label))
    return findings


def conflict_finding(repo: str, token: str, pr: dict) -> list[dict]:
    """충돌난 PR. `mergeable` 은 목록 API 에 없고 개별 조회에서만 나온다.

    GitHub 이 아직 계산 전이면 null 이 온다. 그때는 아무 말도 하지 않는다
    (없는 걸 있다고 하는 쪽보다 한 번 거르는 쪽이 낫다 — 다음 날 10시에 다시 본다).
    """
    try:
        detail = gh(f"/repos/{repo}/pulls/{pr['number']}", token)
    except urllib.error.HTTPError:
        return []
    if detail.get("mergeable") is not False:
        return []
    return [{
        "color": COLOR_WARN,
        "headline": "⚔️ 충돌이 나 머지할 수 없는 PR 이 있습니다",
        "pr": pr,
        "detail": "develop 를 머지해 충돌을 푼 뒤 다시 올려 주세요",
        "mention": dm.mention(pr["user"]["login"]),
    }]


def weekly_deadlines(now: datetime, main_pr: dict | None, deadline_label: str | None) -> list[dict]:
    """팀 일정 마감 알림. 시각이 고정이라 구간 계산이 필요 없다.

    수 18:00 1차 PR · 토 10:00 2차 재리뷰 요청 · 일 23:59 main 머지
    (`_local/주차별_PR/README.md` 의 일정표)

    - 이른 경고(3·2·4시간 전)는 멘션 없이 채널에만 띄운다.
    - 1시간 전에는 테크리더를 직접 멘션한다. 마감을 넘긴 적이 있어서 생긴 알림이다.
    """
    k = now.astimezone(KST)
    weekday, hour = k.weekday(), k.hour  # 월=0
    out: list[dict] = []

    if (weekday, hour) == (2, 15) and main_pr is None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 3시간 뒤 1차 PR 마감입니다 (수 18:00) — main PR 이 아직 없습니다",
            "pr": None,
            "detail": "develop → main PR 을 올려야 멘토 리뷰가 시작됩니다",
        })
    if (weekday, hour) == (5, 8) and main_pr is not None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 2시간 뒤 2차 재리뷰 요청 마감입니다 (토 10:00)",
            "pr": main_pr,
            "detail": "재리뷰 코멘트 + 멘토 리뷰 재요청",
        })
    if (weekday, hour) == (6, 20) and main_pr is not None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 오늘 23:59 이 main 머지 마감입니다",
            "pr": main_pr,
            "detail": "멘토 승인 여부 확인 후 머지",
        })

    if deadline_label:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": f"⏰ 1시간 뒤 {deadline_label}",
            "pr": main_pr,
            "detail": ("main PR 이 아직 없습니다" if main_pr is None
                       else "남은 리뷰·코멘트를 마감 전에 정리해 주세요"),
            "mention": dm.tech_leads(),
        })
    return out


def to_payload(findings: list[dict]) -> dict:
    embeds = []
    for f in findings[:10]:  # 디스코드 embed 상한
        pr = f["pr"]
        fields = [{"name": "상황", "value": f["detail"], "inline": False}]
        if pr:
            fields.insert(0, {
                "name": "작성자", "value": dm.name_of(pr["user"]["login"]), "inline": True,
            })
            fields.insert(1, {
                "name": "브랜치", "value": pr["head"]["ref"], "inline": True,
            })
        embed = {
            "title": f"#{pr['number']} {pr['title']}" if pr else "main PR 없음",
            "color": f["color"],
            "fields": fields,
        }
        if pr:  # url 을 null 로 보내면 디스코드가 400 을 낸다. 없으면 키째로 뺀다.
            embed["url"] = pr["html_url"]
        embeds.append(embed)

    head = findings[0]["headline"] if len(findings) == 1 else f"확인이 필요한 PR {len(findings)}건"

    # 멘션은 content 에 있어야 울린다. embed 안의 <@id> 는 알림이 가지 않는다.
    seen, mention_parts = set(), []
    for f in findings[:10]:
        for token in (f.get("mention") or "").split():
            if token not in seen:
                seen.add(token)
                mention_parts.append(token)
    content = head + ("\n" + " ".join(mention_parts) if mention_parts else "")
    return {"content": content, "embeds": embeds,
            "allowed_mentions": {"parse": ["users"]}}


def post(webhook: str, payload: dict) -> None:
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
    ap.add_argument("--now", help="이 시각인 것처럼 굴린다 (ISO8601). 테스트용")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo:
        print("GITHUB_TOKEN 과 GITHUB_REPOSITORY 가 필요합니다", file=sys.stderr)
        return 1

    now = parse_ts(args.now) if args.now else datetime.now(timezone.utc)

    try:
        findings = build_findings(repo, token, now)
    except urllib.error.HTTPError as e:
        print(f"GitHub API 실패: {e.code} {e.reason}", file=sys.stderr)
        return 1

    if not findings:
        print(f"알릴 것 없음 ({now.astimezone(KST):%Y-%m-%d %H:%M} KST)")
        return 0

    payload = to_payload(findings)
    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
    if not webhook:
        # 운영진 공용 웹훅으로 흘러가는 사고를 막기 위해 다른 웹훅을 대신 쓰지 않는다.
        print("DISCORD_WEBHOOK_TEAM 이 없어 전송을 건너뜁니다")
        return 0
    post(webhook, payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
