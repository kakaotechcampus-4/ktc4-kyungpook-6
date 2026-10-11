/**
 * 주간 코드 리뷰 사이클. `_local/코드 리뷰 가이드.md` 의 일정표를 그대로 옮겼다.
 *
 * 일정이 바뀌면 여기만 고친다. `.github/scripts/remind_discord.py` 의 DEADLINES 와
 * 같은 일정을 보지만, 저쪽은 알림을 "언제 보낼지"를, 여기는 "언제까지인지"를 다룬다.
 */

import { toKstParts } from "./time.ts";

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

export interface Milestone {
    /** 0=일 … 6=토 (KST 기준) */
    weekday: number;
    hour: number;
    minute: number;
    who: "팀장(PM)" | "멘토님";
    what: string;
}

/** 수 18:00 1차 PR · 목 22:00 멘토 1차 리뷰 · 토 10:00 재리뷰 요청 · 일 10:00 approve · 일 23:59 머지 */
export const MILESTONES: Milestone[] = [
    { weekday: 3, hour: 18, minute: 0, who: "팀장(PM)", what: "1차 PR (리뷰 요청)" },
    { weekday: 4, hour: 22, minute: 0, who: "멘토님", what: "1차 리뷰 완료" },
    { weekday: 6, hour: 10, minute: 0, who: "팀장(PM)", what: "2차 PR (재리뷰 요청)" },
    { weekday: 0, hour: 10, minute: 0, who: "멘토님", what: "2차 리뷰 + Approve" },
    { weekday: 0, hour: 23, minute: 59, who: "팀장(PM)", what: "main 으로 머지" },
];

const DAY = ["일", "월", "화", "수", "목", "금", "토"];

/** 지금(KST) 기준으로 각 마일스톤까지 남은 밀리초. 지난 것은 다음 주로 넘긴다. */
function msUntil(m: Milestone, nowMs: number): number {
    const k = toKstParts(nowMs);
    const kstNow = new Date(Date.UTC(k.year, k.month - 1, k.day, k.hour, k.minute));
    const weekday = kstNow.getUTCDay();
    let days = (m.weekday - weekday + 7) % 7;
    const target = new Date(kstNow);
    target.setUTCDate(target.getUTCDate() + days);
    target.setUTCHours(m.hour, m.minute, 0, 0);
    if (target.getTime() <= kstNow.getTime()) target.setUTCDate(target.getUTCDate() + 7);
    return target.getTime() - kstNow.getTime();
}

export function nextMilestone(nowMs: number): { m: Milestone; leftMs: number } {
    let best = { m: MILESTONES[0], leftMs: Number.POSITIVE_INFINITY };
    for (const m of MILESTONES) {
        const leftMs = msUntil(m, nowMs);
        if (leftMs < best.leftMs) best = { m, leftMs };
    }
    return best;
}

export function label(m: Milestone): string {
    const mm = m.minute === 0 ? "" : `:${String(m.minute).padStart(2, "0")}`;
    return `${DAY[m.weekday]} ${m.hour}${mm ? mm : ":00"}`;
}

export function human(ms: number): string {
    const total = Math.floor(ms / 60_000);
    const d = Math.floor(total / (60 * 24));
    const h = Math.floor((total % (60 * 24)) / 60);
    const mi = total % 60;
    if (d > 0) return `${d}일 ${h}시간`;
    if (h > 0) return `${h}시간 ${mi}분`;
    return `${mi}분`;
}

/** 전체 일정표. `/일정` 이 그대로 출력한다. */
export function table(): string {
    return MILESTONES.map((m) => `- **${label(m)}** · ${m.who} — ${m.what}`).join("\n");
}

export { KST_OFFSET_MS };
