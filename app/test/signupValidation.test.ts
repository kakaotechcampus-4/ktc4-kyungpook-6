import { describe, expect, it } from "vitest";
import { CLOSE_TIME_OPTIONS, OPEN_TIME_OPTIONS } from "../src/utils/businessHours";
import { formatBizNo, formatPhone, formatStorePhone } from "../src/utils/format";
import {
  validateBizNo,
  validateBusinessHour,
  validateEmail,
  validatePassword,
  validatePasswordConfirm,
  validatePhone,
  validateMenuName,
  validateRepresentativeName,
  validateStoreAddress,
  validateStoreName,
  validateStorePhone,
} from "../src/utils/signupValidation";

describe("validateEmail", () => {
  it("이메일 형식이면 통과", () => expect(validateEmail("ellanoh04@naver.com")).toBeNull());
  it("앞뒤 공백은 무시한다(백엔드도 strip 한다)", () => expect(validateEmail("  a@b.co  ")).toBeNull());
  it.each(["matna_chick", "a@b", "@b.com", "a b@c.com", ""])("%s 는 형식 오류", (value) =>
    expect(validateEmail(value)).toBe("이메일 형식으로 입력해주세요"));
  it("254자를 넘으면 오류(백엔드 @Size(max = 254))", () =>
    expect(validateEmail(`${"a".repeat(250)}@b.com`)).not.toBeNull());
});

describe("validatePassword (백엔드 @NotBlank·@Size(8~72)·72바이트)", () => {
  it.each(["abcdefgh", "12345678", "a".repeat(72), "비밀번호비밀번호"])("%s 는 통과", (value) =>
    expect(validatePassword(value)).toBeNull());
  it("공백만 있으면 입력 요청", () => expect(validatePassword("        ")).toBe("비밀번호를 입력해주세요"));
  it.each([
    ["7자", "abc1234"],
    ["73자", "a".repeat(73)],
  ])("%s 이면 길이 오류", (_, value) =>
    expect(validatePassword(value)).toBe("비밀번호는 8~72자로 입력해주세요"));
  it("72자 이하라도 72바이트를 넘으면 오류(한글 25자 = 75바이트)", () =>
    expect(validatePassword("가".repeat(25))).toBe("비밀번호가 너무 길어요"));
});

describe("validatePasswordConfirm", () => {
  it("같으면 통과", () => expect(validatePasswordConfirm("abcd1234", "abcd1234")).toBeNull());
  it("다르면 오류", () =>
    expect(validatePasswordConfirm("abcd1234", "adfevfb")).toBe("비밀번호가 일치하지 않아요"));
  it("비어 있으면 오류", () => expect(validatePasswordConfirm("", "")).not.toBeNull());
});

describe("validatePhone (백엔드 PhoneNormalizer.MOBILE)", () => {
  it.each(["010-1234-5678", "01012345678", "011-123-4567", "019-1234-5678"])("%s 는 통과", (value) =>
    expect(validatePhone(value)).toBeNull());
  it.each(["02-123-4567", "012-1234-5678", "010-123", ""])("%s 는 오류", (value) =>
    expect(validatePhone(value)).not.toBeNull());
});

describe("validateBizNo", () => {
  it("숫자 10자리면 통과(하이픈 무시)", () => expect(validateBizNo("123-45-67890")).toBeNull());
  it("10자리가 아니면 오류", () => expect(validateBizNo("123-45-6789")).not.toBeNull());
});

describe("대표자명·상호명", () => {
  it("공백만 있으면 오류", () => {
    expect(validateRepresentativeName("   ")).not.toBeNull();
    expect(validateStoreName("   ")).not.toBeNull();
  });
  it("글자나 숫자가 없으면 오류(백엔드 HAS_LETTER_OR_DIGIT)", () =>
    expect(validateStoreName("---")).not.toBeNull());
  it.each([
    ["줄바꿈", "상호\n상호"],
    ["한글 채움 문자", "\u3164"],
    ["결합 글자 연결자", "상호\u034F상호"],
    ["폭 없는 공백(서식 문자)", "상호\u200B상호"],
    ["겹쳐 쓴 부호 3개", "a\u0301\u0301\u0301"],
  ])("%s 가 있으면 오류(백엔드 NO_INVISIBLE_CHARACTERS·NO_STACKED_MARKS)", (_, value) =>
    expect(validateStoreName(value)).not.toBeNull());
  it("겹쳐 쓴 부호 2개까지는 통과", () => expect(validateStoreName("a\u0301\u0301")).toBeNull());
  it("길이 제한(대표자 50, 상호 200)", () => {
    expect(validateRepresentativeName("가".repeat(51))).not.toBeNull();
    expect(validateStoreName("가".repeat(201))).not.toBeNull();
    expect(validateRepresentativeName("노은서")).toBeNull();
    expect(validateStoreName("상호상호")).toBeNull();
  });
});

describe("formatPhone", () => {
  it.each([
    ["010", "010"],
    ["0101234", "010-1234"],
    ["01012345", "010-1234-5"],
    ["01012345678", "010-1234-5678"],
    ["0111234567", "011-123-4567"],
    ["010123456789", "010-1234-5678"],
    ["010-1234-5678", "010-1234-5678"],
  ])("%s → %s", (input, expected) => expect(formatPhone(input)).toBe(expected));
});

describe("formatBizNo", () => {
  it.each([
    ["123", "123"],
    ["12345", "123-45"],
    ["1234567890", "123-45-67890"],
    ["12345678901", "123-45-67890"],
  ])("%s → %s", (input, expected) => expect(formatBizNo(input)).toBe(expected));
});

describe("formatStorePhone (유선·대표번호도 받는다)", () => {
  it.each([
    ["02", "02"],
    ["02123", "02-123"],
    ["021234", "02-123-4"],
    ["021234567", "02-123-4567"],
    ["0212345678", "02-1234-5678"],
    ["0531234567", "053-123-4567"],
    ["05312345678", "053-1234-5678"],
    ["15881234", "1588-1234"],
    ["158812345", "1588-1234"],
    ["01012345678", "010-1234-5678"],
  ])("%s → %s", (input, expected) => expect(formatStorePhone(input)).toBe(expected));
});

describe("validateStorePhone (백엔드 @NotBlank·20자 이하)", () => {
  it.each(["053-123-4567", "02-123-4567", "1588-1234", "010-1234-5678"])("%s 는 통과", (value) =>
    expect(validateStorePhone(value)).toBeNull());
  it.each(["", "053-12", "1588-123"])("%s 는 오류", (value) => expect(validateStorePhone(value)).not.toBeNull());
});

describe("validateStoreAddress (백엔드 @NotBlank·500자 이하)", () => {
  it("주소가 있으면 통과", () => expect(validateStoreAddress("대구광역시 북구 대학로 80")).toBeNull());
  it("공백만 있으면 오류", () => expect(validateStoreAddress("   ")).not.toBeNull());
  it("500자를 넘으면 오류", () => expect(validateStoreAddress("가".repeat(501))).not.toBeNull());
});

describe("validateMenuName (백엔드 MenuRequest — 상호명과 같은 글자 규칙, 50자 이하)", () => {
  it("메뉴 이름이면 통과", () => expect(validateMenuName("후라이드 치킨")).toBeNull());
  it.each([
    ["공백만", "   "],
    ["글자 없음", "---"],
    ["줄바꿈", "치킨\n치킨"],
    ["51자", "가".repeat(51)],
  ])("%s 이면 오류", (_, value) => expect(validateMenuName(value)).not.toBeNull());
});

describe("validateBusinessHour (백엔드 BusinessHourRequest)", () => {
  it("요일과 시간을 고르면 통과", () =>
    expect(validateBusinessHour({ day: "MON", open: "11:00", close: "21:00" })).toBeNull());
  it("자정에 닫기(24:00)·다음 날 닫기·24시간 영업은 통과", () => {
    expect(validateBusinessHour({ day: "FRI", open: "18:00", close: "24:00" })).toBeNull();
    expect(validateBusinessHour({ day: "FRI", open: "21:00", close: "02:00" })).toBeNull();
    expect(validateBusinessHour({ day: "SUN", open: "00:00", close: "24:00" })).toBeNull();
  });
  it("요일을 안 골랐으면 오류", () =>
    expect(validateBusinessHour({ day: "", open: "11:00", close: "21:00" })).toBe("요일을 골라주세요"));
  it("시간을 안 골랐으면 오류", () =>
    expect(validateBusinessHour({ day: "MON", open: "", close: "21:00" })).toBe("시간을 골라주세요"));
  it("닫는 시간 00:00 은 오류(자정은 24:00)", () =>
    expect(validateBusinessHour({ day: "MON", open: "11:00", close: "00:00" })).not.toBeNull());
  it("여는 시간과 닫는 시간이 같으면 오류(백엔드 isOpenDifferentFromClose)", () =>
    expect(validateBusinessHour({ day: "MON", open: "11:00", close: "11:00" })).toBe(
      "여는 시간과 닫는 시간이 같아요",
    ));
});

describe("영업시간 고르기 목록", () => {
  it("여는 시간은 00:00 ~ 23:30, 닫는 시간은 00:30 ~ 24:00 (30분 간격)", () => {
    expect(OPEN_TIME_OPTIONS[0]).toBe("00:00");
    expect(OPEN_TIME_OPTIONS.at(-1)).toBe("23:30");
    expect(CLOSE_TIME_OPTIONS[0]).toBe("00:30");
    expect(CLOSE_TIME_OPTIONS.at(-1)).toBe("24:00");
    expect(OPEN_TIME_OPTIONS).toHaveLength(48);
  });
  it("목록의 모든 시간이 백엔드 형식을 통과한다", () => {
    for (const open of OPEN_TIME_OPTIONS) {
      expect(validateBusinessHour({ day: "MON", open, close: "24:00" })).toBeNull();
    }
    for (const close of CLOSE_TIME_OPTIONS) {
      expect(validateBusinessHour({ day: "MON", open: "00:15", close })).toBeNull();
    }
  });
});
