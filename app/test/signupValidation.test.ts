import { describe, expect, it } from "vitest";
import { formatBizNo, formatPhone } from "../src/utils/format";
import {
  validateBizNo,
  validateEmail,
  validatePassword,
  validatePasswordConfirm,
  validatePhone,
  validateRepresentativeName,
  validateStoreName,
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
