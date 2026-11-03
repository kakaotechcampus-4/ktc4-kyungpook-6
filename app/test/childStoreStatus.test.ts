import { describe, expect, it } from "vitest";
import type { BusinessHour } from "../src/services/store";
import { getChildStoreOpenStatus } from "../src/utils/childStoreStatus";

// 2026-10-26 은 월요일이다. 기기 시간대 그대로 만든다.
const at = (day: number, time: string) => {
  const [hours, minutes] = time.split(":").map(Number);
  return new Date(2026, 9, day, hours, minutes);
};
const MON = 26;
const TUE = 27;

const scheduled = (businessHours: BusinessHour[]) => ({
  openMode: "SCHEDULE" as const,
  manualOpen: false,
  businessHours,
});

describe("getChildStoreOpenStatus", () => {
  const breakTime = scheduled([
    { day: "MON", open: "11:00", close: "14:00" },
    { day: "MON", open: "17:00", close: "21:00" },
  ]);

  it("영업시간 안이면 영업중이다", () => {
    expect(getChildStoreOpenStatus(breakTime, at(MON, "12:00"))).toBe("OPEN");
  });

  it("오늘 아직 열 시간이 남았으면 영업 전이다 — 브레이크타임 포함", () => {
    expect(getChildStoreOpenStatus(breakTime, at(MON, "09:00"))).toBe("BEFORE_OPEN");
    expect(getChildStoreOpenStatus(breakTime, at(MON, "15:00"))).toBe("BEFORE_OPEN");
  });

  it("오늘 영업이 다 끝났으면 영업종료다", () => {
    expect(getChildStoreOpenStatus(breakTime, at(MON, "21:00"))).toBe("ENDED");
  });

  it("오늘 요일에 등록된 시간이 없으면 오늘 휴무다", () => {
    expect(getChildStoreOpenStatus(breakTime, at(TUE, "12:00"))).toBe("CLOSED_TODAY");
  });

  it("자정을 넘기는 영업은 다음 날 새벽까지 영업중이고, 닫은 뒤에는 그날 기준으로 본다", () => {
    const overnight = scheduled([{ day: "MON", open: "17:00", close: "03:00" }]);
    expect(getChildStoreOpenStatus(overnight, at(MON, "23:30"))).toBe("OPEN");
    expect(getChildStoreOpenStatus(overnight, at(TUE, "02:59"))).toBe("OPEN");
    // 화요일에는 등록된 시간이 없다.
    expect(getChildStoreOpenStatus(overnight, at(TUE, "03:00"))).toBe("CLOSED_TODAY");
  });

  it("직접 켜고 끄기면 영업시간과 상관없이 스위치대로다", () => {
    const hours = breakTime.businessHours;
    expect(getChildStoreOpenStatus({ openMode: "MANUAL", manualOpen: false, businessHours: hours }, at(MON, "12:00"))).toBe(
      "BREAK",
    );
    expect(getChildStoreOpenStatus({ openMode: "MANUAL", manualOpen: true, businessHours: hours }, at(TUE, "12:00"))).toBe(
      "OPEN",
    );
  });
});
