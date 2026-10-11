import { describe, expect, it } from "vitest";
import { formatKst, parseDueAt, toKstParts } from "../src/time.ts";

/** 기준 시각: 2026-10-01 14:30 KST (= 05:30 UTC). */
const NOW = Date.UTC(2026, 9, 1, 5, 30);
/** KST 벽시계 값을 epoch ms 로. */
const kst = (y: number, mo: number, d: number, h: number, mi: number) =>
    Date.UTC(y, mo - 1, d, h, mi) - 9 * 60 * 60 * 1000;

const ok = (input: string) => {
    const r = parseDueAt(input, NOW);
    if (!r.ok) throw new Error(`파싱 실패: ${input} — ${r.reason}`);
    return r.dueAt;
};

describe("상대 시각", () => {
    it("분 단위를 더한다", () => {
        expect(ok("30분후")).toBe(NOW + 30 * 60_000);
    });

    it("시간 단위를 더한다", () => {
        expect(ok("2시간뒤")).toBe(NOW + 2 * 60 * 60_000);
    });

    it("공백이 있어도 읽는다", () => {
        expect(ok("30 분 후")).toBe(NOW + 30 * 60_000);
    });

    it("0분 뒤는 즉시 발송이라 거부한다", () => {
        const r = parseDueAt("0분후", NOW);
        expect(r.ok).toBe(false);
    });
});

describe("시각만 적은 경우", () => {
    it("아직 오지 않은 시각은 오늘로 본다", () => {
        expect(ok("18:00")).toBe(kst(2026, 10, 1, 18, 0));
    });

    it("이미 지난 시각은 내일로 넘긴다 — 새벽에 거는 예약이 과거가 되지 않게 한다", () => {
        expect(ok("09:00")).toBe(kst(2026, 10, 2, 9, 0));
    });

    it("기준 시각과 같은 분은 지난 것으로 보고 내일로 넘긴다", () => {
        expect(ok("14:30")).toBe(kst(2026, 10, 2, 14, 30));
    });
});

describe("날짜를 적은 경우", () => {
    it("내일·모레를 읽는다", () => {
        expect(ok("내일 09:00")).toBe(kst(2026, 10, 2, 9, 0));
        expect(ok("모레 09:00")).toBe(kst(2026, 10, 3, 9, 0));
    });

    it("월-일을 읽고 올해로 본다", () => {
        expect(ok("10-02 09:00")).toBe(kst(2026, 10, 2, 9, 0));
        expect(ok("10/02 09:00")).toBe(kst(2026, 10, 2, 9, 0));
    });

    it("연-월-일을 읽는다", () => {
        expect(ok("2026-12-25 09:00")).toBe(kst(2026, 12, 25, 9, 0));
    });

    it("달을 넘기는 날짜도 넘긴다", () => {
        expect(ok("2026-10-31 23:59")).toBe(kst(2026, 10, 31, 23, 59));
    });

    it("날짜를 적었는데 과거면 거부한다 — 오타로 보기 때문이다", () => {
        const r = parseDueAt("2026-09-30 09:00", NOW);
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.reason).toContain("이미 지난");
    });
});

describe("잘못된 입력", () => {
    it.each(["", "   ", "abc", "25:00", "12:60", "13-45 09:00", "09:0", "9시", "내일"])(
        "%s 는 거부한다",
        (input) => {
            expect(parseDueAt(input, NOW).ok).toBe(false);
        },
    );
});

describe("표시", () => {
    it("KST 로 보여준다", () => {
        expect(formatKst(NOW)).toBe("2026-10-01 14:30");
    });

    it("UTC 자정을 넘기는 시각도 KST 날짜로 보여준다", () => {
        // 2026-10-01 15:00 UTC = 2026-10-02 00:00 KST
        expect(formatKst(Date.UTC(2026, 9, 1, 15, 0))).toBe("2026-10-02 00:00");
    });

    it("쪼갠 값과 표시가 맞는다", () => {
        expect(toKstParts(NOW)).toEqual({ year: 2026, month: 10, day: 1, hour: 14, minute: 30 });
    });
});
