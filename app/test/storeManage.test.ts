import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import type { OperatingInfoRequest } from "../src/services/store";
import {
  formatTimeInput,
  nextBusinessHour,
  toSaveErrorMessage,
  validateOperatingInfo,
} from "../src/utils/storeManage";

const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const httpError = (status: number, data: unknown = undefined) => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data,
  });
};

const valid: OperatingInfoRequest = {
  phone: "053-123-4567",
  businessHours: [{ day: "MON", open: "11:00", close: "21:00" }],
  menus: [{ name: "김치찌개", onSale: true }],
};

describe("formatTimeInput", () => {
  it("숫자 네 자리를 HH:mm 으로 만든다", () => {
    expect(formatTimeInput("1130")).toBe("11:30");
    expect(formatTimeInput("11:3")).toBe("11:3");
    expect(formatTimeInput("1")).toBe("1");
    expect(formatTimeInput("12345")).toBe("12:34");
  });
});

describe("nextBusinessHour", () => {
  it("마지막 줄의 다음 요일에 같은 시간으로 채운다", () => {
    expect(nextBusinessHour([{ day: "SAT", open: "12:00", close: "20:00" }])).toEqual({
      day: "SUN",
      open: "12:00",
      close: "20:00",
    });
  });

  it("일요일 다음은 월요일이다", () => {
    expect(nextBusinessHour([{ day: "SUN", open: "10:00", close: "22:00" }]).day).toBe("MON");
  });

  it("줄이 없으면 월요일 09:00~18:00", () => {
    expect(nextBusinessHour([])).toEqual({ day: "MON", open: "09:00", close: "18:00" });
  });
});

describe("validateOperatingInfo", () => {
  it("백엔드 규칙을 지키면 null", () => {
    expect(validateOperatingInfo(valid)).toBeNull();
  });

  it("자정 마감(24:00)·다음 날 마감·24시간 영업을 받는다", () => {
    const hours = [
      { day: "MON", open: "18:00", close: "24:00" },
      { day: "TUE", open: "21:00", close: "02:00" },
      { day: "WED", open: "00:00", close: "24:00" },
    ] as const;
    expect(validateOperatingInfo({ ...valid, businessHours: [...hours] })).toBeNull();
  });

  it("전화번호가 비면 막는다", () => {
    expect(validateOperatingInfo({ ...valid, phone: "  " })).toContain("전화번호");
  });

  it("영업시간·메뉴가 하나도 없으면 막는다", () => {
    expect(validateOperatingInfo({ ...valid, businessHours: [] })).toContain("영업시간");
    expect(validateOperatingInfo({ ...valid, menus: [] })).toContain("메뉴");
  });

  it("닫는 시간 00:00 은 막는다 — 자정 마감은 24:00 으로 적는다", () => {
    const result = validateOperatingInfo({
      ...valid,
      businessHours: [{ day: "FRI", open: "18:00", close: "00:00" }],
    });
    expect(result).toContain("금요일 닫는 시간");
  });

  it("다 적지 않은 시간과 여는 시간 24:00 은 막는다", () => {
    expect(
      validateOperatingInfo({ ...valid, businessHours: [{ day: "MON", open: "11:3", close: "21:00" }] }),
    ).toContain("여는 시간");
    expect(
      validateOperatingInfo({ ...valid, businessHours: [{ day: "MON", open: "24:00", close: "21:00" }] }),
    ).toContain("여는 시간");
  });

  it("여는 시간과 닫는 시간이 같으면 막는다", () => {
    expect(
      validateOperatingInfo({ ...valid, businessHours: [{ day: "MON", open: "11:00", close: "11:00" }] }),
    ).toContain("같아요");
  });
});

describe("toSaveErrorMessage", () => {
  it("501 은 아직 저장되지 않았다고 알린다", () => {
    expect(toSaveErrorMessage(httpError(501))).toContain("저장되지 않았어요");
  });

  it("검증 실패면 errors 의 첫 문구를 쓴다", () => {
    const error = httpError(400, {
      type: TYPE_BASE + "invalid-request",
      title: "요청 값이 올바르지 않습니다",
      status: 400,
      errors: [{ field: "businessHours[1].open", message: "여는 시간은 00:00 ~ 23:59 사이의 HH:mm 이어야 합니다" }],
    });
    expect(toSaveErrorMessage(error)).toBe("여는 시간은 00:00 ~ 23:59 사이의 HH:mm 이어야 합니다");
  });

  it("errors 가 없으면 title 을 쓴다", () => {
    const error = httpError(403, { type: TYPE_BASE + "forbidden", title: "접근 권한이 없습니다", status: 403 });
    expect(toSaveErrorMessage(error)).toBe("접근 권한이 없습니다");
  });

  it("응답이 없으면 연결 실패 문구", () => {
    const config = { headers: new AxiosHeaders() };
    expect(toSaveErrorMessage(new AxiosError("Network Error", "ERR_NETWORK", config))).toContain("연결하지 못했어요");
  });
});
