#!/usr/bin/env python3
"""사람이 손써야 하는 PR 만 골라 디스코드로 알린다.

봇이 아니라 GitHub Actions + 웹훅이다. 알림만 보내면 되니 24시간 켜둘 서버가 필요 없다.

**언제 도나** — cron(`0 * * * *`)으로는 모자라서 트리거가 셋이다.
`Notify Discord (team PR)`·`Test` 가 끝날 때도 `workflow_run` 으로 깨운다.
**cron 이 적힌 대로 돌지 않기 때문이다** — 자세한 건 `load_since()` 주석에.

**같은 건을 두 번 알리지 않는 방법** — 이번 실행이 책임지는 구간을
**`(직전 성공 실행, 지금]`** 으로 잡고(`load_since()` · `window_start()`), 그 구간 안에서
임계값을 넘긴 것만 고른다. 구간이 겹치지 않으니 몇 번을 더 돌든 한 번만 나간다.
상태를 저장할 곳이 없어도 중복이 안 생긴다. 고정 시각(마감·점검)도 같은 구간으로 본다
(`slot_due()`).

**어떻게 보내나** — **건마다 메시지 하나**다(`to_payloads()`). 멘션은 content 에,
무슨 일·어느 PR 은 embed 에 담는다. 묶어 보내면 멘션이 맨 위에 뭉쳐 누가 어느 PR 을
봐야 하는지 안 보이고, embed 안의 멘션은 아예 울리지 않는다.

멘션 대상은 저장소 시크릿 `DISCORD_MEMBERS` 가 정한다(`discord_members.py`).
저장소에는 두지 않는다 — 공개 저장소라 깃허브 계정·디스코드 계정·실명이 한 줄에 묶인
목록이 이력에 남는다. 형식은 `.github/discord-members.example.json`.

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
import time as time_mod
import urllib.error
import urllib.request
from datetime import datetime, time, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import discord_members as dm  # noqa: E402
import discord_webhook as dw  # noqa: E402

KST = timezone(timedelta(hours=9))
API = "https://api.github.com"

# 팀 내부 리뷰 기한은 "PR 작성 후 24시간"이다(코드 리뷰 가이드 · 팀원 간 리뷰 규칙).
# 24시간에 처음 알리면 그때가 이미 기한이라 늦다. 그래서 두 단계로 나눈다.
NO_REVIEW_FIRST_HOURS = 1    # 1차 독촉 — 지정된 리뷰어를 깨운다
NO_REVIEW_SECOND_HOURS = 4   # 2차 독촉 — 작성자도 같이 부른다
# 리뷰어가 **아무도 지정되지 않은** PR 은 아무의 일도 아닌 상태다. 리뷰가 늦는 것보다
# 이쪽이 더 막힌 상태라, 여기서만 테크리더를 부른다.
NO_REVIEWER_HOURS = 2
# 코드 리뷰 가이드의 팀 규칙은 "리뷰 기한 PR 작성 후 24시간". 1·4시간 독촉을 지나고도
# 리뷰가 없으면 그 뒤로는 아무 말이 없었다 — 실제로 24시간·19시간째 리뷰 0건인 PR 이 생겼다
# (2026-10-07). 기한을 넘긴 PR 은 **하루 한 번 정해진 시각에** 다시 부른다.
#
# 매번 보지 않는 이유 — 하루 종일 같은 PR 로 울리면 알림이 무뎌진다. 하루 한 번이면
# 그 PR 당 정확히 한 번이라 중복도 없다.
STALE_REVIEW_HOURS = 24
STALE_CHECK_HOUR = 10   # KST. 조용한 시간 밖이라 따로 밀 필요가 없다

# 승인됐는데 머지되지 않고 이만큼 지나면 알린다 (시간)
APPROVED_UNMERGED_HOURS = 6
# 충돌을 훑는 시각 (KST). 충돌은 "언제 깨졌는지" 타임스탬프가 없어 구간 계산을 못 한다.
# 그래서 하루 한 번 이 시각에 전수로 본다 — 할 말이 생긴 PR 은 그때마다 따로 확인한다.
CONFLICT_CHECK_HOUR = 10

# 조용한 시간 (KST). 이 사이에 임계값을 넘긴 건은 울리지 않고 QUIET_END 로 미뤄서
# 한 번에 보낸다. 1시간 독촉을 넣으면서 새벽 3시에 멘션이 가는 일이 생겨서 둔다.
QUIET_START_HOUR = 0
QUIET_END_HOUR = 8

# 멘토 리뷰 사이클 (`_local/코드 리뷰 가이드.md`). 월=0
#   수 18:00 1차 PR · 목 22:00 멘토 1차 리뷰 · 토 10:00 재리뷰 요청
#   일 10:00 멘토 approve · 일 23:59 main 머지
# (요일, 1시간 전 시각, 마감 설명)
DEADLINES = [
    (5, 9, "오늘 10:00 2차 재리뷰 요청 마감"),
    (6, 23, "오늘 23:59 main 머지 마감"),
]

# 점검 리포트를 보내는 시각. "마감이다"가 아니라 **지금 뭐가 됐고 뭐가 안 됐는지**를
# 찍어 준다. 수요일은 1차 PR 을 쓰기 직전, 일요일 아침은 머지까지 남은 것을 본다.
# (요일, 시각): (제목, 마감 설명)
READINESS = {
    (2, 17): ("1차 PR 마감 1시간 전", "오늘 18:00 까지 develop → main PR"),
    (6, 9): ("main 머지 준비 점검", "오늘 23:59 까지 main 머지"),
}
# 멘토 쪽은 시각으로 보지 않는다. 리뷰가 언제 올지 정해져 있지 않고, 재촉할 일도 아니다.
# 멘토가 남긴 리뷰·코멘트는 **이 채널로 아예 알리지 않는다** — 운영진 워크플로
# (notify-discord.yml)가 #pr-alert-경북대 로 이미 보내서 두 번 울린다.
# 거르는 쪽은 pr_event_notify.py 의 is_outsider() 다.

# 웹훅은 기본적으로 **웹훅 자신의 이름**(운영진이 만들 때 붙인 이름)으로 글을 쓴다.
# 메시지마다 덮어쓸 수 있어서, 봇과 같은 이름·아바타로 맞춘다. 채널에서 보면
# 알림과 /예약 응답이 같은 "사랑이"로 보인다.
# 아바타를 바꾸면 해시도 바뀐다 — 그때 이 URL 을 같이 고칠 것.
WEBHOOK_NAME = "사랑이"
WEBHOOK_AVATAR = ("https://cdn.discordapp.com/avatars/1555129530186731520/"
                  "add6bacc3fd09363b755ef9dbe1bced6.webp?size=128")

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


def wake_at(crossed: datetime) -> datetime:
    """임계값을 넘은 시각을 조용한 시간 밖으로 민다.

    새벽에 넘긴 건은 그 시각이 아니라 QUIET_END_HOUR 에 한꺼번에 알린다.
    여러 건이 몰려도 payload 가 묶어서 한 메시지로 나간다.

    ⚠️ **통과 시각이 아니라 "실제로 나가는 cron 시각"을 보고 민다.** cron 은 정각에만
    도니까, 23:23 에 넘긴 건은 00:00 에 나간다 — 통과 시각(23시)만 보면 조용한 시간이
    아니라서 안 밀리고, 결국 자정에 멘션이 울린다. 실제로 PR #66(21:23 생성, 리뷰어
    미지정 2시간)이 00:00 에 울렸다. 23:00~23:59 에 넘기는 건은 전부 이렇게 된다.
    """
    k = crossed.astimezone(KST)
    # 통과 직후의 정각 = 이 건을 실어 나를 cron. 정각에 딱 맞춰 넘겼으면 그 정각이다.
    slot = k.replace(minute=0, second=0, microsecond=0)
    if slot != k:
        slot += timedelta(hours=1)
    if QUIET_START_HOUR <= slot.hour < QUIET_END_HOUR:
        # slot 의 날짜를 그대로 쓴다 — 자정을 넘긴 건은 그 다음 날 아침이 맞다.
        return slot.replace(hour=QUIET_END_HOUR)
    return k


CATCH_UP = False   # --catch-up 이면 시간 구간을 무시하고 "이미 넘긴 것"을 전부 본다

# 직전 성공 실행 시각. 이번 실행이 책임지는 구간은 (SINCE, now] 다.
# None 이면 알아내지 못한 것이고, 그때는 "지난 한 시간"으로 되돌아간다(안전한 쪽).
SINCE: datetime | None = None

# 구간 상한. 워크플로가 오래 멈췄다 돌아왔을 때 며칠 치가 한꺼번에 쏟아지는 걸 막는다.
MAX_WINDOW_HOURS = 24

WORKFLOW_FILE = "remind-discord-team.yml"

# 구간을 앞당기는 실행 종류. 수동 실행(workflow_dispatch)은 빼둔다 — 점검 발송 한 번이
# 구간을 삼켜 그 사이 알림이 사라지면 안 된다.
WINDOW_EVENTS = {"schedule", "workflow_run"}

# 건별로 쪼개 보내므로 연속 전송이 생긴다. 디스코드 웹훅은 초당 5건쯤에서 429 를 준다.
SEND_GAP_SECONDS = 0.4


def load_since(repo: str, token: str, now: datetime) -> datetime | None:
    """직전 **성공한 예약 실행**의 시각.

    🚨 **왜 필요한가** — cron 이 적힌 대로 돌지 않는다. `0 * * * *` 인데 실제로는
    5~7시간에 한 번 돌았다(2026-10-07 확인: 34.6시간 동안 7번, 기대치의 20%).
    GitHub 의 예약 실행은 부하가 걸리면 밀리거나 **통째로 건너뛴다.**

    "매시간 도니까 각 건은 1시간 구간을 정확히 한 번 지나간다"가 원래 이 스크립트의 핵심
    아이디어였는데, 그 구간이 통째로 없어지면 **알림이 영영 사라진다.** 상태를 저장하지
    않는 설계라 되살릴 길도 없다. 실제로 수요일 17:00 준비 점검이 그렇게 날아갔다.

    그래서 "지난 한 시간" 대신 **"직전 실행 이후"** 를 구간으로 쓴다. 5시간을 건너뛰어도
    그 사이에 임계값을 넘긴 건이 전부 잡히고, 구간이 겹치지 않으니 여전히 한 번만 나간다.

    이번 실행은 아직 성공이 아니라서 `status=success` 로 거르면 자연히 빠진다.

    🚨 **보내는 실행만 센다** (`WINDOW_EVENTS`). cron 이 모자라서 `workflow_run` 으로도
    도는데, 예약 실행만 기준으로 삼으면 그 사이 여러 번 돌 때 **같은 구간을 반복해서
    보낸다.** 반대로 `workflow_dispatch` 까지 세면 점검 발송 한 번이 구간을 앞당겨
    그 사이 건을 삼킨다 — 그래서 둘 다 아닌 것만 고른다.
    """
    try:
        runs = gh(f"/repos/{repo}/actions/workflows/{WORKFLOW_FILE}/runs"
                  f"?status=success&per_page=20", token)
    except urllib.error.HTTPError as e:
        print(f"::notice::직전 실행 시각을 못 읽었습니다({e.code}). 한 시간 구간으로 돕니다.")
        return None
    items = [r for r in (runs.get("workflow_runs") or [])
             if r.get("event") in WINDOW_EVENTS]
    if not items:
        return None
    items.sort(key=lambda r: r["created_at"], reverse=True)
    since = parse_ts(items[0]["created_at"])
    floor = now - timedelta(hours=MAX_WINDOW_HOURS)
    if since < floor:
        print(f"::warning::직전 실행이 {MAX_WINDOW_HOURS}시간보다 오래됐습니다 "
              f"({since:%m-%d %H:%M} UTC). 구간을 {MAX_WINDOW_HOURS}시간으로 자릅니다.")
        return floor
    return since


def window_start(now: datetime) -> datetime:
    """이번 실행이 책임지는 구간의 시작. SINCE 가 없으면 "지난 한 시간"."""
    if SINCE is not None:
        return SINCE.astimezone(KST)
    k = now.astimezone(KST)
    return k.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)


def due_now(created: datetime, threshold: int, now: datetime) -> bool:
    """`created + threshold` 를 (조용한 시간을 피해서) **이번 구간 안에** 넘겼는가."""
    wake = wake_at(created + timedelta(hours=threshold))
    k = now.astimezone(KST)
    if CATCH_UP:
        return wake <= k      # 밀린 건을 한 번에 — 워크플로가 멈췄다 돌아왔을 때
    if SINCE is None:
        return wake <= k < wake + timedelta(hours=1)
    return window_start(now) < wake <= k


def slot_due(now: datetime, hour: int, weekday: int | None = None) -> bool:
    """`(요일,) 시각` 고정 슬롯을 **이번 구간 안에** 지나왔는가.

    `k.hour == hour` 로 보면 그 시각에 실행이 없을 때 슬롯이 통째로 날아간다.
    cron 이 5~7시간에 한 번 도는 지금은 그게 보통이다.
    """
    k = now.astimezone(KST)
    if SINCE is None:
        return k.hour == hour and (weekday is None or k.weekday() == weekday)
    start = window_start(now)
    day = start.date()
    while day <= k.date():
        slot = datetime.combine(day, time(hour), tzinfo=KST)
        if start < slot <= k and (weekday is None or slot.weekday() == weekday):
            return True
        day += timedelta(days=1)
    return False


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
        (label for wd, hour, label in DEADLINES if slot_due(now, hour, wd)), None
    )

    pulls = gh(f"/repos/{repo}/pulls?state=open&per_page=100", token)

    main_pr = None
    dev: list[tuple[dict, list]] = []   # 열린 develop PR 과 그 리뷰 — 점검 리포트에서 쓴다
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
        dev.append((pr, reviews))
        opened_hours = (now - parse_ts(pr["created_at"])).total_seconds() / 3600
        state = latest_review_state(reviews)
        # 이 PR 에 대해 할 말을 따로 모은다. 충돌이면 통째로 갈아끼운다(아래).
        # 이 PR 에 대해 할 말. 충돌이면 통째로 갈아끼운다(아래).
        pr_findings: list[dict] = []

        created = parse_ts(pr["created_at"])
        assigned = reviewers(pr)

        if not assigned:
            # 리뷰어가 없으면 아무도 안 본다. 리뷰 독촉 대신 "정해 달라"고 한다.
            if due_now(created, NO_REVIEWER_HOURS, now):
                pr_findings.append({
                    "color": COLOR_DEADLINE,
                    "headline": "🔴 리뷰어가 아직 없습니다",
                    "pr": pr,
                    "detail": f"열린 지 {int(opened_hours)}시간 · 아무도 보고 있지 않습니다",
                    "action": "같은 파트 팀원 1명 이상을 리뷰어로 지정해 주세요",
                    # 부를 사람은 PR 을 올린 본인이다. 리뷰어를 정하는 것도 본인 몫이라
                    # 테크리더까지 부르면 매번 같은 세 명이 울려 알림이 무뎌진다.
                    "mention": dm.mention(pr["user"]["login"]),
                })
        elif not reviews:
            # 리뷰어는 있는데 아직 안 봤다. 두 번까지만 깨운다.
            second = due_now(created, NO_REVIEW_SECOND_HOURS, now)
            # 2차가 지금 울리면 1차는 보내지 않는다. 같은 PR 에 두 줄이 나가는 걸 막는다.
            #
            # 조용한 시간(00~08시)에 넘긴 건은 08:00 으로 밀리는데, **1차와 2차가 같이 밀리면
            # 아침에 같은 PR 로 두 번 울린다** — 새벽 1시에 올라온 PR 이 그렇다(1시간→02:00,
            # 4시간→05:00, 둘 다 08:00 행). 예전에는 --catch-up 일 때만 막아서 이 경우가 샜다.
            if due_now(created, NO_REVIEW_FIRST_HOURS, now) and not second:
                pr_findings.append({
                    "color": COLOR_INFO,
                    "headline": "🕐 리뷰를 기다리고 있습니다",
                    "pr": pr,
                    "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건",
                    "action": "리뷰 부탁드립니다",
                    "mention": dm.mentions(assigned),
                })
            if second:
                pr_findings.append({
                    "color": COLOR_WARN,
                    "headline": f"🟠 {NO_REVIEW_SECOND_HOURS}시간째 리뷰가 없습니다",
                    "pr": pr,
                    "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건",
                    "action": "오늘 안에 보기 어려우면 다른 분께 넘겨 주세요",
                    "mention": dm.mentions(assigned + [pr["user"]["login"]]),
                })

        # 기한(24시간)을 넘기고도 리뷰가 없는 PR. **하루 한 번**만 부른다.
        #
        # 리뷰어가 있든 없든 본다 — 리뷰어가 없는 채로 묵은 PR 이 더 나쁜 상태인데,
        # 그쪽은 2시간 독촉 뒤로 아무 말이 없었다.
        # 1·4시간 독촉과 겹치지 않는다: 한 PR 이 4시간 미만이면서 24시간 초과일 수 없다.
        if (not reviews and slot_due(now, STALE_CHECK_HOUR)
                and opened_hours >= STALE_REVIEW_HOURS):
            # 일수를 머리말에 넣지 않는다. cron 이 불규칙해서 하루 독촉이 18시간 간격으로
            # 떨어지면 "2일째"가 두 번 나간다. 경과 시간은 아래 detail 에 정확히 적는다.
            pr_findings.append({
                "color": COLOR_WARN,
                "headline": "⌛ 리뷰 기한을 넘겼습니다",
                "pr": pr,
                "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건 "
                          f"(팀 기준 리뷰 기한은 {STALE_REVIEW_HOURS}시간)",
                "action": "오늘 보기 어려우면 다른 분께 넘기거나 PR 을 닫아 주세요",
                "mention": dm.mentions(people(pr)),
            })

        # 마감 1시간 전에 아직 리뷰가 하나도 없는 PR — 작성자와 리뷰어를 같이 부른다.
        # 멘토 리뷰 PR(base=main)은 위에서 이미 걸러져 여기 오지 않는다.
        if deadline_label and not reviews:
            pr_findings.append({
                "color": COLOR_DEADLINE,
                "headline": f"⏰ 1시간 뒤 {deadline_label} — 아직 리뷰가 없습니다",
                "pr": pr,
                "detail": f"열린 지 {int(opened_hours)}시간 · 리뷰 0건",
                "action": "이번 주 PR 에 넣을 거면 지금 리뷰해야 합니다",
                "mention": dm.mentions(people(pr)),
            })

        if state == "APPROVED":
            since = approved_at(reviews)
            # due_now 를 쓴다. 예전엔 여기만 in_band(옛 1시간 구간)였는데, cron 이
            # 5~7시간에 한 번 도는 지금은 그 한 시간에 실행이 없으면 **영영 안 나간다.**
            # due_now 는 구간(직전 실행 이후)과 조용한 시간을 둘 다 본다.
            if since and due_now(since, APPROVED_UNMERGED_HOURS, now):
                pr_findings.append({
                    "color": COLOR_INFO,
                    "headline": "🟢 승인됐는데 아직 머지되지 않았습니다",
                    "pr": pr,
                    "detail": f"승인 후 {APPROVED_UNMERGED_HOURS}시간 경과",
                    "action": "머지하셔도 됩니다",
                    "mention": dm.mention(pr["user"]["login"]),
                })

        # 🧨 **충돌이면 위에서 모은 말을 전부 버리고 충돌만 알린다.**
        #    충돌난 PR 은 GitHub 이 merge ref 를 만들지 못해 **워크플로를 아예 돌리지 않는다**
        #    — 테스트도, 알림도 안 온다(#76 에서 체크 0개였다). 볼 것이 없는데 리뷰어를
        #    부르면 엉뚱한 사람을 부르는 꼴이다. 움직여야 할 사람은 작성자다.
        #
        #    예전에는 하루 한 번(10시)만 봤다. 그러면 오후에 깨진 PR 을 다음 날까지 아무도
        #    모른다. 할 말이 생긴 시각에 같이 확인해서, 늦어도 그 PR 의 첫 독촉과 함께 잡는다.
        if pr_findings or slot_due(now, CONFLICT_CHECK_HOUR):
            if is_conflicted(repo, token, pr):
                pr_findings = [conflict_finding(pr)]
        findings.extend(pr_findings)

    findings.extend(weekly_deadlines(now, main_pr, deadline_label))
    findings.extend(readiness_report(repo, token, now, main_pr, dev))
    return findings


def is_conflicted(repo: str, token: str, pr: dict) -> bool:
    """충돌인가. `mergeable` 은 목록 API 에 없고 개별 조회에서만 나온다.

    GitHub 이 아직 계산 전이면 null 이 온다. 그때는 **아니라고 본다** — 없는 걸 있다고
    말하는 쪽보다 한 번 거르는 쪽이 낫다. 다음 실행에서 다시 보므로 결국 잡힌다.
    """
    try:
        detail = gh(f"/repos/{repo}/pulls/{pr['number']}", token)
    except urllib.error.HTTPError:
        return False
    return detail.get("mergeable") is False


def conflict_finding(pr: dict) -> dict:
    return {
        "color": COLOR_WARN,
        "headline": "🧨 충돌이 나서 CI 가 돌지 않았습니다",
        "pr": pr,
        "detail": "충돌나면 GitHub 이 테스트도 알림도 아예 돌리지 않습니다 (체크 0개)",
        "action": "develop 를 머지해 충돌을 푸세요. 그때 CI 가 다시 돕니다",
        "mention": dm.mention(pr["user"]["login"]),
    }


def weekly_deadlines(now: datetime, main_pr: dict | None, deadline_label: str | None) -> list[dict]:
    """팀 일정 마감 알림. 고정 시각이지만 `slot_due()` 로 본다 — cron 이 그 시각에
    안 돌 수 있어서, "그 시각이 이번 구간 안에 들어왔는가" 로 봐야 안 사라진다.

    수 18:00 1차 PR · 토 10:00 2차 재리뷰 요청 · 일 23:59 main 머지
    (`_local/주차별_PR/README.md` 의 일정표)

    - 이른 경고(3·2·4시간 전)는 멘션 없이 채널에만 띄운다.
    - 1시간 전에는 테크리더를 직접 멘션한다. 마감을 넘긴 적이 있어서 생긴 알림이다.
    """
    out: list[dict] = []

    if slot_due(now, 15, 2) and main_pr is None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 3시간 뒤 1차 PR 마감입니다 (수 18:00)",
            "pr": None,
            "detail": "멘토 PR 이 아직 없습니다. develop → main PR 을 올려야 리뷰가 시작됩니다",
        })
    if slot_due(now, 8, 5) and main_pr is not None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 2시간 뒤 2차 재리뷰 요청 마감입니다 (토 10:00)",
            "pr": main_pr,
            "detail": "반영한 refactor PR 링크를 멘토 코멘트에 답글로 남기고 재리뷰를 요청합니다",
        })
    if slot_due(now, 20, 6) and main_pr is not None:
        out.append({
            "color": COLOR_DEADLINE,
            "headline": "⏰ 오늘 23:59 이 main 머지 마감입니다",
            "pr": main_pr,
            "detail": "멘토 승인을 확인하고 머지해 주세요",
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


def readiness_report(repo: str, token: str, now: datetime,
                     main_pr: dict | None, dev: list[tuple[dict, list]]) -> list[dict]:
    """수 17:00 · 일 09:00 — "다 됐는지" 를 항목별로 찍어 준다.

    재촉이 아니라 상태 보고다. 수요일에는 1차 PR 을 쓰기 직전에 무엇이 남았는지,
    일요일 아침에는 머지까지 무엇이 남았는지 본다.
    """
    k = now.astimezone(KST)
    slot = next((v for (wd, hour), v in READINESS.items() if slot_due(now, hour, wd)), None)
    if not slot:
        return []
    title, deadline = slot
    lines = [f"마감: {deadline}", ""]
    ok = True

    if (k.weekday(), k.hour) == (2, 17):
        lines.append(f"{'✅' if main_pr else '❌'} 멘토 PR: "
                     + (f"#{main_pr['number']} 올라와 있음" if main_pr else "아직 없음"))
        ok = ok and bool(main_pr)

        try:
            cmp_ = gh(f"/repos/{repo}/compare/main...develop", token)
            lines.append(f"ℹ️ develop 이 main 보다 {cmp_.get('ahead_by', '?')}커밋 앞서 있음")
        except urllib.error.HTTPError:
            pass

        unreviewed = [pr for pr, rv in dev if not rv]
        if dev:
            lines.append(f"{'⚠️' if unreviewed else '✅'} 열린 develop PR {len(dev)}건"
                         + (f" · 그중 리뷰 0건이 {len(unreviewed)}건" if unreviewed else ""))
            for pr, _ in dev[:5]:
                lines.append(f"      #{pr['number']} {pr['title'][:40]}")
            lines.append("      → 이번 주 PR 에 포함할지 먼저 정하세요")
            ok = False
        else:
            lines.append("✅ 열린 develop PR 없음")

        lines.append(check_state(repo, token, "develop"))
    else:
        if not main_pr:
            lines.append("❌ 멘토 PR 이 없습니다")
            ok = False
        else:
            reviews = gh(f"/repos/{repo}/pulls/{main_pr['number']}/reviews?per_page=100", token)
            team = set(dm._table().get("members", {}))
            outside = [r for r in reviews if (r.get("user") or {}).get("login") not in team]
            approved = any(r.get("state") == "APPROVED" for r in outside)
            lines.append(f"{'✅' if approved else '❌'} 멘토 approve: "
                         + ("받음" if approved else "아직 없음"))
            ok = ok and approved

            waiting = unanswered(repo, token, main_pr["number"], team)
            lines.append(f"{'⚠️' if waiting else '✅'} 답변이 필요한 멘토 코멘트: "
                         + (f"{waiting}건" if waiting else "없음"))
            ok = ok and not waiting

            try:
                detail = gh(f"/repos/{repo}/pulls/{main_pr['number']}", token)
                m = detail.get("mergeable")
                lines.append({True: "✅ 머지 가능", False: "❌ 충돌 — 풀어야 합니다"}
                             .get(m, "ℹ️ 머지 가능 여부 계산 중"))
                ok = ok and (m is not False)
            except urllib.error.HTTPError:
                pass

    return [{
        "color": COLOR_INFO if ok else COLOR_DEADLINE,
        "headline": ("✅ 준비됐습니다 — " if ok else "📋 ") + title,
        "pr": None,
        "title": title,
        "detail": "\n".join(lines),
        "mention": dm.tech_leads(),
    }]


def check_state(repo: str, token: str, ref: str) -> str:
    """브랜치 머리의 체크 결과 한 줄."""
    try:
        runs = gh(f"/repos/{repo}/commits/{ref}/check-runs", token).get("check_runs", [])
    except urllib.error.HTTPError:
        return "ℹ️ CI 상태를 읽지 못했습니다"
    if not runs:
        return "ℹ️ CI 기록 없음"
    bad = [r["name"] for r in runs
           if r.get("status") == "completed" and r.get("conclusion") not in ("success", "neutral", "skipped")]
    return f"❌ {ref} CI 실패: {', '.join(bad[:3])}" if bad else f"✅ {ref} CI 통과"


def needs_reply(comment: dict) -> bool:
    """답이 필요해 보이는가. 물음표가 있는 것만 센다.

    8주차 멘토의 마지막 코멘트는 "맞습니다 ㅎㅎ 이번주도 화이팅입니다!" 였다.
    "마지막 말이 멘토 것이면 미응답"으로 세면 이런 인사까지 잡혀서, 매주 일요일
    아침마다 가짜 경고가 뜬다. 되물음에는 물음표가 있다.
    """
    return "?" in (comment.get("body") or "") or "？" in (comment.get("body") or "")


def unanswered(repo: str, token: str, number: int, team: set) -> int:
    """멘토가 **물어본 뒤** 팀이 아무 말도 안 한 스레드 수.

    코드 코멘트는 `in_reply_to_id` 로 스레드를 묶는다. 일반 코멘트는 스레드가 없어서
    **마지막 한 건만** 본다 — 그 뒤에 팀 코멘트가 없으면 미응답 1건으로 센다.
    """
    def login(c):
        return (c.get("user") or {}).get("login")

    waiting = 0
    try:
        rc = gh(f"/repos/{repo}/pulls/{number}/comments?per_page=100", token)
        threads: dict[int, list] = {}
        for c in rc:
            threads.setdefault(c.get("in_reply_to_id") or c["id"], []).append(c)
        for items in threads.values():
            items.sort(key=lambda c: c["created_at"])
            if login(items[-1]) not in team and needs_reply(items[-1]):
                waiting += 1

        ic = gh(f"/repos/{repo}/issues/{number}/comments?per_page=100", token)
        if ic and login(ic[-1]) not in team and needs_reply(ic[-1]):
            waiting += 1
    except urllib.error.HTTPError:
        return 0
    return waiting


def to_payloads(findings: list[dict]) -> list[dict]:
    """**건마다 메시지 하나.** 멘션은 content 에, 무슨 일·어느 PR 은 embed 에.

    예전에는 여러 건을 한 메시지에 묶고 embed 를 안 썼다. 이유가 둘이었다 —
    embed 안의 멘션은 울리지 않고, 묶어서 보내면 멘션이 맨 위에 뭉쳐 누가 어느 PR 을
    봐야 하는지 안 보였다.

    **건별로 쪼개면 둘 다 풀린다.** 한 메시지에 멘션 하나, embed 하나니까 멘션을
    content 에 올려도 뭉치지 않는다. 스레드에서 메시지가 묶여 보이던 것도 embed
    왼쪽 색 막대가 갈라 준다 — 이벤트 알림(`pr_event_notify.py`)과 같은 모양이 된다.

    한 번에 10건까지. 그보다 많으면 마지막에 남은 수만 적는다.
    """
    shown = findings[:10]
    out = []
    for f in shown:
        pr = f["pr"]
        embed: dict = {"title": f["headline"][:256], "color": f.get("color", COLOR_INFO)}
        desc = []
        if pr:
            desc.append(f"**#{pr['number']} {pr['title']}**")
            embed["url"] = pr["html_url"]
        if f.get("detail"):
            desc.append(f["detail"])
        embed["description"] = "\n".join(desc)[:4000]
        content = " ".join(t for t in (f.get("mention", ""), f.get("action", "")) if t)
        out.append({"content": content[:1900], "embeds": [embed],
                    "allowed_mentions": {"parse": ["users"]}})

    if len(findings) > len(shown):
        out.append({"content": f"…외 {len(findings) - len(shown)}건",
                    "allowed_mentions": {"parse": []}})
    return out


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
        print(f"디스코드 전송 실패: {e.code} {e.reason}\n{e.read().decode(errors='replace')[:400]}")
        # 스레드가 지워졌거나 잠겼으면 **알림이 통째로 사라진다.** 묻히는 것보다 나쁘다.
        if url == webhook:
            raise
        print(f"::warning::{dw.THREAD_ENV} 로 보내지 못해 채널로 보냅니다. "
              "스레드가 지워졌거나 잠긴 건 아닌지 확인하세요.")
        _send(webhook, payload)


def send_test(dry_run: bool = False) -> int:
    """점검용 한 줄을 보낸다. **누른 사람만** 멘션한다.

    왜 있나 — 이 저장소에서 보내 봐야만 드러난 것이 두 번 있었다. 디스코드(Cloudflare)가
    기본 User-Agent 를 403 으로 막은 것, embed 안의 멘션이 울리지 않은 것. 둘 다
    `--dry-run` 으로는 멀쩡해 보였다. 그래서 **실제로 한 번 보내 보는 통로**를 둔다.

    멘션 대상을 입력으로 받지 않는 이유 — 이 저장소는 공개다. 디스코드 ID 를 워크플로
    입력으로 넘기면 Actions 실행 기록에 그대로 남는다. 깃허브 로그인(공개 정보)만 받고
    ID 는 시크릿 매핑에서 찾는다.
    """
    actor = os.environ.get("GITHUB_ACTOR", "").strip()
    who = dm.mention(actor) if actor else ""
    body = ("🧪 점검용 메시지입니다. 이 줄이 스레드 안에 보이면 전송 경로가 살아 있습니다.\n"
            "누른 사람만 멘션했습니다 — 다른 분께는 알림이 가지 않습니다.")
    if who:
        body += f"\n{who}"
    payload = {"content": body, "allowed_mentions": {"parse": ["users"]}}

    if dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
    if not webhook:
        print("DISCORD_WEBHOOK_TEAM 이 없어 전송을 건너뜁니다")
        return 0
    tid = dw.thread_id()
    print(f"보내는 곳: {'스레드 ' + tid if tid else '채널 (DISCORD_THREAD_ID 비어 있음)'}")
    post(webhook, payload)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="보내지 않고 payload 만 출력")
    ap.add_argument("--now", help="이 시각인 것처럼 굴린다 (ISO8601). 테스트용")
    ap.add_argument("--catch-up", action="store_true",
                    help="시간 구간을 무시하고 이미 임계값을 넘긴 건을 한 번에 알린다")
    ap.add_argument("--test", action="store_true",
                    help="PR 을 보지 않고 점검용 메시지 한 줄만 보낸다. "
                         "누른 사람(GITHUB_ACTOR)만 멘션한다")
    args = ap.parse_args()

    if args.test:
        return send_test(dry_run=args.dry_run)

    global CATCH_UP
    CATCH_UP = args.catch_up

    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not token or not repo:
        print("GITHUB_TOKEN 과 GITHUB_REPOSITORY 가 필요합니다", file=sys.stderr)
        return 1

    now = parse_ts(args.now) if args.now else datetime.now(timezone.utc)

    # cron 이 적힌 대로 돌지 않는다(5~7시간에 한 번). "지난 한 시간" 대신 "직전 실행 이후"를
    # 구간으로 써야 건너뛴 시각의 알림이 사라지지 않는다. load_since() 주석 참고.
    global SINCE
    # --now 는 "그 시각인 것처럼" 굴려 보는 디버깅 수단이다. 그때 실제 직전 실행 시각을
    # 읽으면 구간이 **거꾸로 뒤집힌다**(직전 실행이 미래) — 아무것도 안 잡혀서 재현이 안 된다.
    # 그래서 --now 를 주면 구간을 안 쓰고 "지난 한 시간"으로 본다. 예전과 같은 동작이다.
    if not CATCH_UP and not args.now:
        SINCE = load_since(repo, token, now)
        if SINCE:
            gap = (now - SINCE).total_seconds() / 3600
            print(f"직전 실행 {SINCE.astimezone(KST):%m-%d %H:%M} KST ({gap:.1f}시간 전) "
                  f"이후 구간을 봅니다")
        else:
            print("직전 실행을 못 찾아 지난 한 시간만 봅니다")
    elif args.now:
        print("--now 로 굴리는 중이라 구간 대신 '지난 한 시간'으로 봅니다")

    try:
        findings = build_findings(repo, token, now)
    except urllib.error.HTTPError as e:
        print(f"GitHub API 실패: {e.code} {e.reason}", file=sys.stderr)
        return 1

    if not findings:
        print(f"알릴 것 없음 ({now.astimezone(KST):%Y-%m-%d %H:%M} KST)")
        return 0

    payloads = to_payloads(findings)
    if args.dry_run:
        print(json.dumps(payloads, ensure_ascii=False, indent=2))
        return 0

    webhook = os.environ.get("DISCORD_WEBHOOK_TEAM")
    if not webhook:
        # 운영진 공용 웹훅으로 흘러가는 사고를 막기 위해 다른 웹훅을 대신 쓰지 않는다.
        print("DISCORD_WEBHOOK_TEAM 이 없어 전송을 건너뜁니다")
        return 0
    # 하나가 실패해도 **나머지는 보낸다.** 중간에 멈추면 뒤쪽 건이 조용히 사라진다.
    #
    # 그러고 나서 **실행은 실패로 끝낸다.** 그래야 "직전 성공 실행" 시각이 안 올라가고,
    # 다음 실행이 같은 구간을 다시 본다. 일부가 두 번 가는 건 감수한다 —
    # 이 저장소에서 반복해서 아팠던 건 중복이 아니라 **유실**이었다.
    failed = 0
    for i, payload in enumerate(payloads):
        if i:
            time_mod.sleep(SEND_GAP_SECONDS)   # 웹훅 속도 제한을 건드리지 않게 띄운다
        try:
            post(webhook, payload)
        except (urllib.error.HTTPError, urllib.error.URLError, OSError) as e:
            failed += 1
            print(f"::error::{i + 1}번째 메시지 전송 실패: {e}")
    if failed:
        print(f"::error::{len(payloads)}건 중 {failed}건 실패. "
              f"구간을 넘기지 않고 다음 실행에서 다시 봅니다.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
