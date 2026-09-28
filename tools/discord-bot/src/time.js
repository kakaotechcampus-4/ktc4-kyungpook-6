/**
 * 예약 시각을 한국시간으로 해석한다.
 *
 * 받는 형식 세 가지. 팀원이 디코에서 손으로 치는 값이라 관대하게 받는다.
 *   "10:00"              오늘 10시. 이미 지났으면 **내일** 10시로 본다
 *   "09-30 10:00"        올해 9월 30일 10시
 *   "2026-09-30 10:00"   연도까지 지정
 *
 * KST 는 서머타임이 없어서 항상 UTC+9 다. 그래서 타임존 라이브러리 없이
 * 9시간만 더하고 빼면 된다 — Workers 런타임에 tz 데이터가 없어도 정확하다.
 */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 지금이 KST 로 몇 년 몇 월 며칠인지 */
export function kstParts(nowMs) {
  const d = new Date(nowMs + KST_OFFSET_MS);
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate(),
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
  };
}

/** KST 의 달력 값을 UTC epoch(초)로 */
function kstToEpochSec(year, month, day, hour, minute) {
  return Math.floor(Date.UTC(year, month - 1, day, hour, minute, 0) / 1000) - KST_OFFSET_MS / 1000;
}

/**
 * @returns {{ ok: true, dueAt: number } | { ok: false, reason: string }}
 *   dueAt 은 UTC epoch 초.
 */
export function parseSchedule(input, nowMs) {
  const text = String(input ?? "").trim();
  const now = kstParts(nowMs);

  let m = /^(\d{1,2}):(\d{2})$/.exec(text);
  if (m) {
    const [hour, minute] = [Number(m[1]), Number(m[2])];
    if (hour > 23 || minute > 59) return { ok: false, reason: "시·분 범위를 넘었습니다" };
    let dueAt = kstToEpochSec(now.year, now.month, now.day, hour, minute);
    if (dueAt <= Math.floor(nowMs / 1000)) {
      // 오늘 그 시각이 이미 지났다 → 내일로 본다. 새벽에 "10:00" 이라 치면 몇 시간 뒤가 된다.
      dueAt += 24 * 60 * 60;
    }
    return { ok: true, dueAt };
  }

  m = /^(?:(\d{4})[-.\/])?(\d{1,2})[-.\/](\d{1,2})[ T](\d{1,2}):(\d{2})$/.exec(text);
  if (m) {
    const year = m[1] ? Number(m[1]) : now.year;
    const [month, day, hour, minute] = [Number(m[2]), Number(m[3]), Number(m[4]), Number(m[5])];
    if (month < 1 || month > 12 || day < 1 || day > 31) return { ok: false, reason: "날짜가 이상합니다" };
    if (hour > 23 || minute > 59) return { ok: false, reason: "시·분 범위를 넘었습니다" };
    const dueAt = kstToEpochSec(year, month, day, hour, minute);
    if (dueAt <= Math.floor(nowMs / 1000)) return { ok: false, reason: "이미 지난 시각입니다" };
    return { ok: true, dueAt };
  }

  return {
    ok: false,
    reason: '시각 형식을 읽지 못했습니다. `10:00` 이나 `09-30 10:00` 처럼 적어주세요',
  };
}

/** 사람이 읽는 KST 표기 */
export function formatKst(epochSec) {
  const p = kstParts(epochSec * 1000);
  const pad = (n) => String(n).padStart(2, "0");
  const weekday = "일월화수목금토"[new Date(epochSec * 1000 + KST_OFFSET_MS).getUTCDay()];
  return `${p.year}-${pad(p.month)}-${pad(p.day)}(${weekday}) ${pad(p.hour)}:${pad(p.minute)} KST`;
}
