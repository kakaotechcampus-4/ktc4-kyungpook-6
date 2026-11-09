import { describe, expect, it } from "vitest";
import type { BizCertReading } from "../src/services/file";
import {
  fillBlank,
  fillInfoBlanks,
  hasAnyReading,
  toAutofillItems,
} from "../src/utils/bizCertReading";
import { formatOpenDate, isoToOpenDate, openDateToIso } from "../src/utils/format";
import { validateOpenDate } from "../src/utils/signupValidation";

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

describe("toAutofillItems", () => {
  it("올리기 전에는 다섯 항목 모두 못 읽음", () => {
    const items = toAutofillItems(null);
    expect(items.map((item) => item.label)).toEqual([
      "대표자명",
      "사업자등록번호",
      "상호명",
      "사업장 주소",
      "개업일",
    ]);
    expect(items.every((item) => !item.read)).toBe(true);
  });

  it("읽은 칸만 true, 공백뿐인 값은 못 읽은 것으로 본다", () => {
    const items = toAutofillItems(
      reading({ storeName: null, storeAddress: "  " }),
    );
    expect(items.map((item) => item.read)).toEqual([true, true, false, false, true]);
  });
});

describe("개업일", () => {
  it.each([
    ["2021", "2021"],
    ["202103", "2021.03"],
    ["20210315", "2021.03.15"],
    ["2021.03.159", "2021.03.15"],
  ])("%s 는 %s 로 나눈다", (input, expected) => expect(formatOpenDate(input)).toBe(expected));

  it("백엔드 모양과 오간다", () => {
    expect(openDateToIso("2021.03.15")).toBe("2021-03-15");
    expect(isoToOpenDate("2021-03-15")).toBe("2021.03.15");
  });

  it.each(["", "2021.03.15", "2024.02.29", "0001.01.01"])("%s 는 통과(빈 값은 선택이라 통과)", (value) =>
    expect(validateOpenDate(value)).toBeNull());
  it.each(["2021.03", "2021.13.01", "2023.02.29", "0000.01.01", "2021-03-15"])("%s 는 오류", (value) =>
    expect(validateOpenDate(value)).toBe("개업일을 확인해주세요"));
});

describe("fillInfoBlanks (기본 정보를 적고 돌아와 다시 올렸을 때)", () => {
  const info = { representativeName: "김철수", bizNo: "", storeName: "", storeAddress: "", openDate: "" };

  it("적은 값은 두고 빈 칸만 채운다", () => {
    expect(fillInfoBlanks(info, reading())).toEqual({
      representativeName: "김철수",
      bizNo: "123-45-67890",
      storeName: "예시분식",
      storeAddress: "대구광역시 북구 대학로 80",
      openDate: "2019.03.02",
    });
  });

  it("법인 등록증(상호 null)이면 상호명은 비워 두고 주소만 채운다", () => {
    expect(fillInfoBlanks(info, reading({ storeName: null }))).toMatchObject({
      storeName: "",
      storeAddress: "대구광역시 북구 대학로 80",
    });
  });
});
