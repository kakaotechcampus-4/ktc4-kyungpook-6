"""`pr_event_notify.py` 의 리뷰어 지정 알림 회귀 테스트.

여기 있는 것들은 **전부 실제로 사고가 나서 생겼다.** 같은 전제가 세 번 틀렸고,
세 번 다 **알림이 안 가는 쪽**으로 틀렸다.

| 창 | 어디서 깨졌나 |
|---|---|
| 120초 | PR #70 — 56초 뒤에 지정한 리뷰어 알림이 묻혔다 |
| 20초 | PR #84·#85 — 9~10초 뒤에 지정한 리뷰어 알림이 묻혔다 |
| 2초 + 서버 시각 | 지금 |

    pytest .github/scripts/tests
"""

from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pr_event_notify as n  # noqa: E402


def pr(created: str, updated: str) -> dict:
    """리뷰 요청 판정에 필요한 칸만 채운 PR."""
    return {
        "number": 84,
        "title": "[AI] 계약 테스트가 failure 모양 어긋남을 잡게 한다",
        "html_url": "https://github.com/o/r/pull/84",
        "user": {"login": "author"},
        "head": {"ref": "branch"},
        "base": {"ref": "develop"},
        "additions": 389,
        "deletions": 32,
        "changed_files": 4,
        "created_at": created,
        "updated_at": updated,
        "requested_reviewers": [{"login": "reviewer"}],
    }


def request_event(login: str = "reviewer", kind: str = "User") -> dict:
    return {"action": "review_requested", "requested_reviewer": {"login": login, "type": kind}}


# ─────────────────────────────────────────────────────────────────────────────
# 묻히면 안 되는 것들 — 전부 실제 사고다
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "updated,사고",
    [
        ("2026-10-09T07:55:34Z", "PR #84 — 9초 뒤 지정"),
        ("2026-10-09T07:55:35Z", "PR #85 — 10초 뒤 지정"),
        ("2026-10-09T07:56:21Z", "PR #70 — 56초 뒤 지정"),
        ("2026-10-09T08:55:25Z", "한 시간 뒤에 리뷰어를 바꿨다"),
    ],
)
def test_나중에_지정한_리뷰어는_알림이_간다(updated: str, 사고: str) -> None:
    """PR 을 올린 뒤 리뷰어를 넣은 건은 **반드시** 알림이 가야 한다.

    `opened` 는 그때 리뷰어가 없어서 "⚠️ 리뷰어가 없습니다" 를 보냈다. 이걸 묻으면
    **리뷰어가 누군지 아무에게도 안 알려진다.** #84·#85 가 그렇게 묻혔다.
    """
    msg = n.on_review_requested(request_event(), pr("2026-10-09T07:55:25Z", updated))
    assert msg is not None, f"{사고} 가 묻혔습니다"
    assert "리뷰어로 지정" in msg["embeds"][0]["title"]


def test_PR_과_함께_지정한_리뷰어는_한_번만_알린다() -> None:
    """`gh pr create --reviewer` 처럼 원자적으로 지정하면 GitHub 이 두 이벤트를 보낸다.

    그때는 `opened` 가 이미 멘션했으므로 건너뛴다. 이게 이 가드의 **유일한** 목적이다.
    """
    assert n.on_review_requested(
        request_event(), pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:26Z")
    ) is None


def test_방금_올린_PR_에_9초_뒤_지정해도_알림이_간다() -> None:
    """#84·#85 를 **그대로 재현한다.** 위 테스트들과 달리 시각을 '지금' 기준으로 만든다.

    옛 코드는 `now() - created_at` 을 봤다. 과거 시각으로 테스트하면 그 차이가 커져서
    옛 코드도 통과한다 — 그래서 **방금 올린 PR** 이어야 사고가 재현된다.
    옛 코드에서는 age 가 9초라 20초 창에 걸려 묻혔다.
    """
    now = datetime.now(timezone.utc)
    방금 = pr(
        (now - timedelta(seconds=9)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        now.strftime("%Y-%m-%dT%H:%M:%SZ"),
    )
    assert n.on_review_requested(request_event(), 방금) is not None, (
        "방금 올린 PR 에 9초 뒤 리뷰어를 지정한 건이 묻혔습니다 — #84·#85 와 같은 사고입니다"
    )


def test_러너가_늦게_돌아도_판정이_같다() -> None:
    """러너 시계를 쓰면 안 된다 — 큐 대기 시간이 간격에 섞인다.

    `datetime.now()` 로 재던 코드는 **같은 상황인데 러너가 빠르면 묻히고 느리면 나갔다.**
    GitHub 이 서버에서 찍은 두 값만 보면 언제 돌든 결과가 같다.
    """
    같은_PR = pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:26Z")
    assert n.requested_with_pr(같은_PR) is True
    assert n.requested_with_pr(같은_PR) is True  # 몇 번 불러도, 언제 불러도 같다


# ─────────────────────────────────────────────────────────────────────────────
# 멘션할 사람이 없는 경우
# ─────────────────────────────────────────────────────────────────────────────

def test_봇_리뷰어는_부르지_않는다() -> None:
    늦게 = pr("2026-10-09T07:55:25Z", "2026-10-09T07:56:21Z")
    assert n.on_review_requested(request_event("Copilot", "Bot"), 늦게) is None
    assert n.on_review_requested(request_event("some-app", "Bot"), 늦게) is None


def test_팀_단위_리뷰_요청은_건너뛴다() -> None:
    """`requested_team` 으로 오면 `requested_reviewer` 가 없다. 멘션할 개인이 없다."""
    event = {"action": "review_requested", "requested_team": {"slug": "backend"}}
    assert n.on_review_requested(event, pr("2026-10-09T07:55:25Z", "2026-10-09T07:56:21Z")) is None


# ─────────────────────────────────────────────────────────────────────────────
# 모를 때는 보내는 쪽으로 — 묻는 쪽이 아니라
# ─────────────────────────────────────────────────────────────────────────────

@pytest.mark.parametrize(
    "created,updated",
    [
        (None, "2026-10-09T07:55:26Z"),          # created_at 이 없다
        ("2026-10-09T07:55:25Z", None),          # updated_at 이 없다
        ("", ""),                                 # 빈 값
        ("2026-10-09T07:55:25Z", "말이 안 되는 값"),  # 파싱 실패
    ],
)
def test_시각을_못_읽으면_보낸다(created, updated) -> None:
    """판정에 쓸 값이 없으면 **보낸다.** 모를 때 묻어 버리는 것이 세 번 다 사고가 난 방향이다."""
    bad = pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:26Z")
    bad["created_at"], bad["updated_at"] = created, updated
    assert n.requested_with_pr(bad) is False
    assert n.on_review_requested(request_event(), bad) is not None


def test_updated_at_이_created_at_보다_이르면_보낸다() -> None:
    """있을 수 없는 값이지만, 그때도 묻지 않고 보낸다."""
    거꾸로 = pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:20Z")
    assert n.requested_with_pr(거꾸로) is False


# ─────────────────────────────────────────────────────────────────────────────
# opened 쪽 — 묻힌 알림의 반대편
# ─────────────────────────────────────────────────────────────────────────────

def test_리뷰어_없이_올린_PR_은_작성자를_부른다() -> None:
    """#84·#85 가 받은 그 메시지다. 이것만 가고 끝나면 리뷰어를 아무도 모른다."""
    없음 = pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:25Z")
    없음["requested_reviewers"] = []
    msg = n.on_opened(없음)
    assert "리뷰어가 없습니다" in msg["content"]


def test_리뷰어와_함께_올린_PR_은_리뷰어를_부른다() -> None:
    msg = n.on_opened(pr("2026-10-09T07:55:25Z", "2026-10-09T07:55:26Z"))
    assert "리뷰 부탁드립니다" in msg["content"]
