/**
 * 예약 시각 파싱과 표시. 전부 한국 시간(KST, UTC+9) 기준이다.
 *
 * Workers 는 UTC 로 돌고 `Intl` 로 시간대를 다룰 수 있지만, 여기서는 고정 오프셋(+9)으로
 * 계산한다. 한국은 일광절약시간을 쓰지 않아 오프셋이 바뀌지 않기 때문이다 — 팀이 전부
 * 한국에 있으니 시간대를 입력받지 않는다.
 */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;

/** KST 달력 기준의 연·월·일·시·분. */
interface KstParts {
    year: number;
    month: number;
    day: number;
    hour: number;
    minute: number;
}

/** epoch ms 를 KST 달력 값으로 쪼갠다. */
export function toKstParts(epochMs: number): KstParts {
    const d = new Date(epochMs + KST_OFFSET_MS);
    return {
        year: d.getUTCFullYear(),
        month: d.getUTCMonth() + 1,
        day: d.getUTCDate(),
        hour: d.getUTCHours(),
        minute: d.getUTCMinutes(),
    };
}

/** KST 달력 값을 epoch ms 로 되돌린다. 월·일이 범위를 넘으면 `Date.UTC` 가 넘겨 준다. */
function fromKstParts(p: KstParts): number {
    return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute) - KST_OFFSET_MS;
}

/** 사람이 읽는 형식. 예: `2026-10-02 09:00` */
export function formatKst(epochMs: number): string {
    const p = toKstParts(epochMs);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${p.year}-${pad(p.month)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`;
}

export type ParseResult =
    | { ok: true; dueAt: number }
    | { ok: false; reason: string };

const RELATIVE = /^(\d{1,4})\s*(분|시간)\s*(?:뒤|후)$/;
const ABSOLUTE = /^(?:(\d{4})[-/.])?(?:(\d{1,2})[-/.](\d{1,2})\s+)?(\d{1,2}):(\d{2})$/;
const TOMORROW = /^(내일|모레)\s+(\d{1,2}):(\d{2})$/;

/**
 * 예약 시각 문자열을 epoch ms 로 바꾼다.
 *
 * 받는 형식:
 * - `30분후` · `2시간뒤` — 지금부터 상대 시각
 * - `09:00` — 오늘 그 시각. 이미 지났으면 **내일** 같은 시각으로 본다
 * - `내일 09:00` · `모레 09:00`
 * - `10-02 09:00` · `10/02 09:00` — 올해 그 날짜
 * - `2026-10-02 09:00`
 *
 * 과거 시각은 거부한다 — cron 이 바로 집어 보내 버리면 "예약"이 아니라 즉시 발송이 된다.
 * 다만 `09:00` 처럼 날짜를 생략한 경우는 거부하지 않고 다음 날로 넘긴다(오타가 아니라 의도로 본다).
 */
export function parseDueAt(input: string, nowMs: number): ParseResult {
    const text = input.trim().replace(/\s+/g, " ");
    if (text === "") return { ok: false, reason: "시각이 비어 있습니다" };

    const rel = RELATIVE.exec(text);
    if (rel) {
        const amount = Number(rel[1]);
        if (amount === 0) return { ok: false, reason: "0분 뒤는 예약이 아니라 즉시 발송입니다" };
        const unit = rel[2] === "시간" ? 60 * MINUTE_MS : MINUTE_MS;
        return { ok: true, dueAt: nowMs + amount * unit };
    }

    const now = toKstParts(nowMs);

    const rough = TOMORROW.exec(text);
    if (rough) {
        const parsed = buildAbsolute(
            { ...now, day: now.day + (rough[1] === "내일" ? 1 : 2) },
            Number(rough[2]),
            Number(rough[3]),
        );
        return parsed;
    }

    const abs = ABSOLUTE.exec(text);
    if (!abs) {
        return {
            ok: false,
            reason: "시각을 읽지 못했습니다. `30분후` · `09:00` · `내일 09:00` · `10-02 09:00` · `2026-10-02 09:00` 형식으로 넣어 주세요",
        };
    }

    const [, yearRaw, monthRaw, dayRaw, hourRaw, minuteRaw] = abs;
    const hour = Number(hourRaw);
    const minute = Number(minuteRaw);

    // 날짜를 아예 안 적은 경우(`09:00`)만 "지났으면 내일" 규칙을 쓴다.
    if (monthRaw === undefined) {
        const today = buildAbsolute(now, hour, minute);
        if (!today.ok) return today;
        if (today.dueAt > nowMs) return today;
        return buildAbsolute({ ...now, day: now.day + 1 }, hour, minute);
    }

    const result = buildAbsolute(
        {
            ...now,
            year: yearRaw === undefined ? now.year : Number(yearRaw),
            month: Number(monthRaw),
            day: Number(dayRaw),
        },
        hour,
        minute,
    );
    if (!result.ok) return result;
    if (result.dueAt <= nowMs) {
        return { ok: false, reason: `이미 지난 시각입니다 (${formatKst(result.dueAt)})` };
    }
    return result;
}

function buildAbsolute(base: KstParts, hour: number, minute: number): ParseResult {
    if (hour > 23) return { ok: false, reason: `시(時)가 범위를 벗어났습니다: ${hour}` };
    if (minute > 59) return { ok: false, reason: `분이 범위를 벗어났습니다: ${minute}` };
    if (base.month < 1 || base.month > 12) {
        return { ok: false, reason: `월이 범위를 벗어났습니다: ${base.month}` };
    }
    if (base.day < 1 || base.day > 31) {
        return { ok: false, reason: `일이 범위를 벗어났습니다: ${base.day}` };
    }
    return { ok: true, dueAt: fromKstParts({ ...base, hour, minute }) };
}
