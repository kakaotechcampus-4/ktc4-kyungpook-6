import assert from "node:assert/strict";
import { test } from "node:test";

import { formatKst, kstParts, parseSchedule } from "../src/time.js";

/** 2026-09-29(화) 03:00 KST = 2026-09-28T18:00Z — "새벽에 예약을 건다" 는 상황 */
const NOW = Date.UTC(2026, 8, 28, 18, 0, 0);

test("KST 로 날짜를 읽는다", () => {
  assert.deepEqual(kstParts(NOW), { year: 2026, month: 9, day: 29, hour: 3, minute: 0 });
});

test("HH:MM — 오늘 아직 안 지난 시각이면 오늘로 잡는다", () => {
  const r = parseSchedule("10:00", NOW);
  assert.equal(r.ok, true);
  assert.equal(formatKst(r.dueAt), "2026-09-29(화) 10:00 KST");
});

test("HH:MM — 오늘 이미 지난 시각이면 내일로 잡는다", () => {
  // 새벽 3시에 "02:00" 이라 치면 오늘 2시는 지났으므로 내일 2시다
  const r = parseSchedule("02:00", NOW);
  assert.equal(r.ok, true);
  assert.equal(formatKst(r.dueAt), "2026-09-30(수) 02:00 KST");
});

test("MM-DD HH:MM — 올해로 해석한다", () => {
  const r = parseSchedule("09-30 18:00", NOW);
  assert.equal(r.ok, true);
  assert.equal(formatKst(r.dueAt), "2026-09-30(수) 18:00 KST");
});

test("연도까지 지정할 수 있다", () => {
  const r = parseSchedule("2026-10-04 23:59", NOW);
  assert.equal(r.ok, true);
  assert.equal(formatKst(r.dueAt), "2026-10-04(일) 23:59 KST");
});

test("구분자는 - . / 를 모두 받는다", () => {
  for (const s of ["09-30 18:00", "09.30 18:00", "09/30 18:00", "09-30T18:00"]) {
    assert.equal(parseSchedule(s, NOW).ok, true, s);
  }
});

test("이미 지난 날짜는 거부한다", () => {
  const r = parseSchedule("09-28 10:00", NOW);
  assert.equal(r.ok, false);
  assert.match(r.reason, /지난 시각/);
});

test("시·분 범위를 넘으면 거부한다", () => {
  assert.equal(parseSchedule("25:00", NOW).ok, false);
  assert.equal(parseSchedule("10:75", NOW).ok, false);
});

test("읽을 수 없는 형식은 이유를 알려준다", () => {
  for (const s of ["", "내일 아침", "10시", "2026-09-30", undefined]) {
    const r = parseSchedule(s, NOW);
    assert.equal(r.ok, false, String(s));
    assert.ok(r.reason.length > 0);
  }
});

test("UTC 로 저장한 값이 KST 로 다시 읽혀야 한다 (9시간 왕복)", () => {
  const r = parseSchedule("2026-12-31 23:59", NOW);
  assert.equal(r.ok, true);
  // KST 12/31 23:59 = UTC 12/31 14:59
  assert.equal(new Date(r.dueAt * 1000).toISOString(), "2026-12-31T14:59:00.000Z");
});
