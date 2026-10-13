"""`remind_discord.py` 의 시각 판정 회귀 테스트.

여기 있는 것들은 **전부 실제로 사고가 나서 생겼다.** 손으로 돌려서 고쳤는데,
자동 테스트가 없으면 다음에 한 줄 고칠 때 조용히 깨진다.

    pytest .github/scripts/tests
"""

from __future__ import annotations

import datetime
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import remind_discord as r  # noqa: E402

KST = r.KST
UTC = datetime.timezone.utc


def at(y, m, d, h, mi=0):
    return datetime.datetime(y, m, d, h, mi, tzinfo=KST)


@pytest.fixture(autouse=True)
def _reset():
    """전역 상태를 테스트마다 되돌린다."""
    before = (r.SINCE, r.CATCH_UP)
    r.SINCE, r.CATCH_UP = None, False
    yield
    r.SINCE, r.CATCH_UP = before


# ── wake_at: 조용한 시간 밀기 ────────────────────────────────────────────

@pytest.mark.parametrize("crossed, expect", [
    # 🐛 23시대에 넘긴 건은 00:00 cron 으로 나가서 자정에 멘션이 울렸다.
    #    통과 시각(23시)만 보면 조용한 시간이 아니라 안 밀렸다. 실제로 PR #66 이 그랬다.
    (at(2026, 10, 5, 23, 23), at(2026, 10, 6, 8)),
    (at(2026, 10, 5, 23, 59), at(2026, 10, 6, 8)),
    # 정각에 딱 맞춰 넘겼으면 그 정각이 자기 cron 이라 안 밀린다
    (at(2026, 10, 5, 23, 0), at(2026, 10, 5, 23, 0)),
    # 새벽에 넘긴 건은 그날 아침으로
    (at(2026, 10, 6, 2, 0), at(2026, 10, 6, 8)),
    (at(2026, 10, 6, 0, 10), at(2026, 10, 6, 8)),
    # 조용한 시간 밖은 그대로
    (at(2026, 10, 6, 7, 30), at(2026, 10, 6, 7, 30)),
    (at(2026, 10, 6, 8, 0), at(2026, 10, 6, 8, 0)),
    (at(2026, 10, 6, 14, 10), at(2026, 10, 6, 14, 10)),
])
def test_조용한_시간을_넘기는_건은_아침으로_밀린다(crossed, expect):
    assert r.wake_at(crossed) == expect


def test_조용한_시간에는_아무것도_울리지_않는다():
    created = at(2026, 10, 5, 21, 23)       # 2시간 뒤 = 23:23 통과
    for hour in range(0, 8):
        assert not r.due_now(created, 2, at(2026, 10, 6, hour)), f"{hour}시에 울렸다"
    assert r.due_now(created, 2, at(2026, 10, 6, 8)), "08시에 나가야 한다"


# ── due_now: 중복 ───────────────────────────────────────────────────────

def test_아침에_1차와_2차가_같이_밀려도_한_번만_나간다():
    """🐛 새벽 1시 PR 은 1시간(02:00)·4시간(05:00)이 둘 다 08:00 으로 밀려 두 줄이 나갔다."""
    created, now = at(2026, 10, 6, 1), at(2026, 10, 6, 8)
    first = r.due_now(created, r.NO_REVIEW_FIRST_HOURS, now)
    second = r.due_now(created, r.NO_REVIEW_SECOND_HOURS, now)
    assert first and second, "둘 다 밀려 같은 시각에 걸리는 상황이어야 한다"
    # 호출부는 2차가 울리면 1차를 보내지 않는다 — 그 규칙이 지켜지는지는 build 쪽 책임이라
    # 여기서는 "둘이 겹치는 상황이 실재한다"는 것만 고정해 둔다.


# ── 구간(SINCE) 기반 판정 ───────────────────────────────────────────────

def test_cron_이_건너뛰어도_그_사이_건이_잡힌다():
    """🐛 cron 이 5~7시간에 한 번 돌아 수요일 17:00 준비 점검이 영영 사라졌다."""
    created = at(2026, 10, 7, 12)           # 4시간 뒤 = 16:00 통과
    # 16:00 에 돌고 다음이 21:00 인 상황
    r.SINCE = at(2026, 10, 7, 15).astimezone(UTC)
    assert r.due_now(created, 4, at(2026, 10, 7, 21)), "건너뛴 구간의 건이 나가야 한다"


def test_구간은_겹치지_않아_두_번_나가지_않는다():
    created = at(2026, 10, 7, 12)
    r.SINCE = at(2026, 10, 7, 15).astimezone(UTC)
    assert r.due_now(created, 4, at(2026, 10, 7, 21))
    r.SINCE = at(2026, 10, 7, 21).astimezone(UTC)   # 다음 실행
    assert not r.due_now(created, 4, at(2026, 10, 8, 3)), "같은 건이 또 나갔다"


def test_SINCE_가_없으면_예전처럼_한_시간_구간():
    created = at(2026, 10, 7, 12)
    assert r.due_now(created, 4, at(2026, 10, 7, 16))
    assert not r.due_now(created, 4, at(2026, 10, 7, 18))


# ── slot_due: 고정 시각 ─────────────────────────────────────────────────

def test_고정_시각은_실행이_늦어도_잡힌다():
    """`k.hour == 17` 로 보면 17시에 실행이 없을 때 통째로 날아간다."""
    r.SINCE = at(2026, 10, 7, 16).astimezone(UTC)
    assert r.slot_due(at(2026, 10, 7, 21), 17, 2), "수요일 17시 슬롯이 나가야 한다"


def test_고정_시각은_요일이_다르면_안_잡힌다():
    r.SINCE = at(2026, 10, 8, 16).astimezone(UTC)   # 목요일
    assert not r.slot_due(at(2026, 10, 8, 21), 17, 2)


def test_고정_시각도_두_번_나가지_않는다():
    r.SINCE = at(2026, 10, 7, 16).astimezone(UTC)
    assert r.slot_due(at(2026, 10, 7, 21), 17, 2)
    r.SINCE = at(2026, 10, 7, 21).astimezone(UTC)
    assert not r.slot_due(at(2026, 10, 8, 3), 17, 2)


def test_SINCE_가_없으면_그_시각에만():
    assert r.slot_due(at(2026, 10, 7, 17), 17, 2)
    assert not r.slot_due(at(2026, 10, 7, 21), 17, 2)


# ── 승인-미머지 ─────────────────────────────────────────────────────────

def test_승인_미머지도_구간으로_본다():
    """🐛 여기만 옛 `in_band`(1시간 구간)를 써서, 그 한 시간에 실행이 없으면 사라졌다."""
    approved = at(2026, 10, 8, 10)          # 6시간 뒤 = 16:00
    r.SINCE = at(2026, 10, 8, 15).astimezone(UTC)   # 15시에 돌고 다음이 21시
    assert r.due_now(approved, r.APPROVED_UNMERGED_HOURS, at(2026, 10, 8, 21))
    r.SINCE = at(2026, 10, 8, 21).astimezone(UTC)
    assert not r.due_now(approved, r.APPROVED_UNMERGED_HOURS, at(2026, 10, 9, 3)), "또 나갔다"


def test_승인_미머지도_조용한_시간을_지킨다():
    """옛 `in_band` 는 조용한 시간을 아예 안 봐서 새벽 3시에 울릴 수 있었다."""
    approved = at(2026, 10, 8, 20)          # 6시간 뒤 = 02:00
    r.SINCE = at(2026, 10, 9, 1).astimezone(UTC)
    assert not r.due_now(approved, r.APPROVED_UNMERGED_HOURS, at(2026, 10, 9, 3))
    r.SINCE = at(2026, 10, 9, 3).astimezone(UTC)
    assert r.due_now(approved, r.APPROVED_UNMERGED_HOURS, at(2026, 10, 9, 8)), "아침에 나가야 한다"


def test_옛_in_band_는_지웠다():
    """남겨 두면 누가 다시 가져다 쓴다. 구간을 안 보고 조용한 시간도 안 본다."""
    assert not hasattr(r, "in_band")


# ── 메시지 모양 ─────────────────────────────────────────────────────────

def _finding(**kw):
    base = {"color": r.COLOR_WARN, "headline": "🟠 테스트", "pr": None,
            "detail": "상세", "action": "해주세요", "mention": "<@111>"}
    base.update(kw)
    return base


def test_멘션은_embed_가_아니라_content_에_있어야_한다():
    """🐛 embed 안의 `<@id>` 는 링크로만 보이고 알림이 가지 않는다."""
    import json
    for p in r.to_payloads([_finding(), _finding(headline="⌛ 둘째")]):
        assert "<@111>" in p["content"], "멘션이 content 에 없다"
        assert "<@" not in json.dumps(p.get("embeds", []), ensure_ascii=False)


def test_건마다_메시지가_하나씩_나간다():
    """묶어 보내면 멘션이 맨 위에 뭉쳐 누가 어느 PR 을 봐야 하는지 안 보인다."""
    payloads = r.to_payloads([_finding(), _finding(), _finding()])
    assert len(payloads) == 3
    assert all(len(p["embeds"]) == 1 for p in payloads)


def test_열_건을_넘으면_나머지는_수만_적는다():
    payloads = r.to_payloads([_finding() for _ in range(13)])
    assert len(payloads) == 11
    assert "외 3건" in payloads[-1]["content"]
    assert payloads[-1]["allowed_mentions"]["parse"] == [], "남은 수 알림은 아무도 안 부른다"


def test_PR_이_있으면_embed_제목에_링크가_걸린다():
    pr = {"number": 7, "title": "제목", "html_url": "https://x/7"}
    p = r.to_payloads([_finding(pr=pr)])[0]
    assert p["embeds"][0]["url"] == "https://x/7"
    assert "#7" in p["embeds"][0]["description"]


# ── 리뷰 독촉 되풀이 (stale_nudge_due) ────────────────────────────────────

def hours(created, now):
    return (now - created).total_seconds() / 3600


def due(created, now):
    return r.stale_nudge_due(created, now, hours(created, now))


def test_2차_독촉_전에는_되풀이_독촉을_보내지_않는다():
    created = at(2026, 10, 14, 7)          # 3시간째
    assert due(created, at(2026, 10, 14, 10)) is False


def test_2차를_지나면_그_뒤로_계속_부른다():
    # 🐛 예전에는 24시간을 넘긴 건만 봐서, 13시에 올라온 PR 은 2차(17시) 뒤로
    #    다음다음 날 아침까지 조용했다. 그 사이 10:00 에는 21시간째였다.
    created = at(2026, 10, 13, 13)
    assert due(created, at(2026, 10, 14, 10)) is True      # 21시간째
    assert due(created, at(2026, 10, 15, 10)) is True      # 45시간째 — 계속


def test_2차_직후_슬롯은_건너뛰고_그_다음_슬롯부터_부른다():
    # 🐛 밤에 올라온 PR 은 2차가 조용한 시간에 걸려 08:00 으로 밀린다.
    #    막지 않으면 08:00·10:00 에 같은 PR 로 두 번 울린다.
    created = at(2026, 10, 13, 21, 52)                     # 2차 = 01:52 → 08:00
    assert due(created, at(2026, 10, 14, 10)) is False     # 2시간 차 — 너무 붙는다
    assert due(created, at(2026, 10, 14, 14)) is True      # 🐛 날짜로 끊던 때는 여기가 죽었다


def test_2차가_같은_날_오전이어도_오후_슬롯은_살아_있다():
    created = at(2026, 10, 14, 5, 30)                      # 2차 = 09:30
    assert due(created, at(2026, 10, 14, 10)) is False     # 30분 차
    assert due(created, at(2026, 10, 14, 14)) is True      # 4시간 반 차


def test_기한_24시간은_부를지가_아니라_말투만_가른다():
    # 24시간 미만이어도 부른다. 24시간은 ⌛/🔔 를 가르는 선일 뿐이다.
    created = at(2026, 10, 13, 13)
    assert hours(created, at(2026, 10, 14, 10)) < r.STALE_REVIEW_HOURS
    assert due(created, at(2026, 10, 14, 10)) is True


def test_독촉_슬롯은_깨어_있는_시간에_4시간_간격이다():
    # PR 사이클이 3일이고 PR 이 마감 몇 시간 전에 올라온다. 하루 한 번으로는
    # 리뷰어가 한 번 놓치면 반나절이 그냥 지나간다.
    slots = r.STALE_CHECK_HOURS
    assert slots == (10, 14, 18, 22)
    assert all(b - a == 4 for a, b in zip(slots, slots[1:]))
    # 조용한 시간(00~08)에 걸리는 슬롯은 없다 — 밀 필요가 없어야 한다
    assert all(not (r.QUIET_START_HOUR <= h < r.QUIET_END_HOUR) for h in slots)


def test_슬롯_간격보다_최소_간격이_좁다():
    # 최소 간격이 슬롯 간격보다 넓으면 2차 뒤 슬롯이 연쇄로 죽는다.
    assert r.STALE_MIN_GAP_HOURS < 4
