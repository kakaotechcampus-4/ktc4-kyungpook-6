import { describe, expect, it } from "vitest";
import type { BizCertReading } from "../src/services/file";
import { fillBlank, hasAnyReading, toSignupExtras } from "../src/utils/bizCertReading";

const reading = (overrides: Partial<BizCertReading> = {}): BizCertReading => ({
  bizNo: "1234567890",
  storeName: "예시분식",
  representativeName: "홍길동",
  storeAddress: "대구광역시 북구 대학로 80",
  openDate: "2019-03-02",
  corpName: null,
  businessType: "음식점업",
  businessItem: "한식, 분식",
  ntsVerification: "UNVERIFIED",
  ...overrides,
});

describe("fillBlank", () => {
  it("빈 칸은 읽은 값으로 채운다", () => {
    expect(fillBlank("", "홍길동")).toBe("홍길동");
  });

  it("공백뿐인 칸도 빈 칸으로 본다", () => {
    expect(fillBlank("  ", "홍길동")).toBe("홍길동");
  });

  it("점주가 적은 값은 덮어쓰지 않는다", () => {
    expect(fillBlank("김철수", "홍길동")).toBe("김철수");
  });

  it.each([null, "", "   "])("못 읽은 값(%j)이면 그대로 둔다", (read) => {
    expect(fillBlank("", read)).toBe("");
  });

  it("읽은 값의 앞뒤 공백은 지운다", () => {
    expect(fillBlank("", " 홍길동 ")).toBe("홍길동");
  });
});

describe("hasAnyReading", () => {
  const empty = reading({
    bizNo: null,
    storeName: null,
    representativeName: null,
    storeAddress: null,
    openDate: null,
    corpName: null,
    businessType: null,
    businessItem: null,
  });

  it("모든 칸이 null 이면 못 읽은 것으로 본다", () => {
    expect(hasAnyReading(empty)).toBe(false);
  });

  it("공백뿐인 칸만 있어도 못 읽은 것으로 본다", () => {
    expect(hasAnyReading({ ...empty, storeName: "  " })).toBe(false);
  });

  it("한 칸이라도 읽었으면 읽은 것으로 본다", () => {
    expect(hasAnyReading({ ...empty, corpName: "주식회사 예시" })).toBe(true);
  });
});

describe("toSignupExtras", () => {
  it("읽은 선택 값만 싣고 못 읽은 칸은 뺀다", () => {
    expect(toSignupExtras(reading())).toEqual({
      openDate: "2019-03-02",
      businessType: "음식점업",
      businessItem: "한식, 분식",
    });
  });

  it("못 읽었으면 아무것도 싣지 않는다", () => {
    expect(toSignupExtras(null)).toEqual({});
  });

  it("공백뿐인 값은 싣지 않는다", () => {
    expect(toSignupExtras(reading({ corpName: "  ", businessType: " " }))).not.toHaveProperty("corpName");
  });
});
