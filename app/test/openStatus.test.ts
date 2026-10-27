import { describe, expect, it } from "vitest";
import type { BusinessHour } from "../src/services/store";
import { isOpenAt } from "../src/utils/openStatus";

// 2026-10-26 은 월요일이다. 기기 시간대 그대로 만든다.
const at = (day: number, time: string) => {
  const [hours, minutes] = time.split(":").map(Number);
  return new Date(2026, 9, day, hours, minutes);
};
const MON = 26;
const TUE = 27;

describe("isOpenAt", () => {
  const lunch: BusinessHour[] = [{ day: "MON", open: "11:00", close: "21:00" }];

  it("여는 시각부터 닫는 시각 전까지 영업 중이다", () => {
    expect(isOpenAt(lunch, at(MON, "10:59"))).toBe(false);
    expect(isOpenAt(lunch, at(MON, "11:00"))).toBe(true);
    expect(isOpenAt(lunch, at(MON, "20:59"))).toBe(true);
    expect(isOpenAt(lunch, at(MON, "21:00"))).toBe(false);
  });

  it("줄이 없는 요일은 휴무다", () => {
    expect(isOpenAt(lunch, at(TUE, "12:00"))).toBe(false);
  });

  it("브레이크타임 사이에는 영업 종료다", () => {
    const hours: BusinessHour[] = [
      { day: "MON", open: "11:00", close: "15:00" },
      { day: "MON", open: "17:00", close: "21:00" },
    ];
    expect(isOpenAt(hours, at(MON, "16:00"))).toBe(false);
    expect(isOpenAt(hours, at(MON, "17:30"))).toBe(true);
  });

  it("자정을 넘기면 다음 날 새벽까지 영업 중이다", () => {
    const hours: BusinessHour[] = [{ day: "MON", open: "21:00", close: "02:00" }];
    expect(isOpenAt(hours, at(MON, "23:00"))).toBe(true);
    expect(isOpenAt(hours, at(TUE, "01:59"))).toBe(true);
    expect(isOpenAt(hours, at(TUE, "02:00"))).toBe(false);
    // 다음 날 저녁에는 화요일 줄이 없으니 닫혀 있다.
    expect(isOpenAt(hours, at(TUE, "21:30"))).toBe(false);
  });

  it("자정에 닫는 24:00 과 24시간 영업 00:00~24:00", () => {
    expect(isOpenAt([{ day: "MON", open: "18:00", close: "24:00" }], at(MON, "23:59"))).toBe(true);
    expect(isOpenAt([{ day: "MON", open: "18:00", close: "24:00" }], at(TUE, "00:00"))).toBe(false);
    expect(isOpenAt([{ day: "MON", open: "00:00", close: "24:00" }], at(MON, "00:00"))).toBe(true);
  });

  it("일요일 밤에 열어 월요일 새벽에 닫는 줄도 본다", () => {
    const hours: BusinessHour[] = [{ day: "SUN", open: "22:00", close: "03:00" }];
    expect(isOpenAt(hours, at(MON, "02:00"))).toBe(true);
  });
});
