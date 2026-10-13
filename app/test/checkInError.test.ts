import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import { toCheckInErrorMessage } from "../src/utils/checkInError";
import { formatHourMinute } from "../src/utils/format";

/*
  백엔드가 실제로 보내는 에러 본문 모양(docs/에러_처리_가이드.md)으로 만든다.
  type 베이스 URI 와 title 은 ErrorCode.java 값 그대로다.
*/
const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const problem = (status: number, typeName: string, title: string) => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data: { type: TYPE_BASE + typeName, title, status, instance: "/api/stores/1/check-ins" },
  });
};

const INVALID_QR = "유효하지 않은 QR이에요. 다시 확인해주세요.";

describe("toCheckInErrorMessage", () => {
  it("400 invalid-qr-token 은 유효하지 않은 QR 이라고 알린다", () => {
    expect(toCheckInErrorMessage(problem(400, "invalid-qr-token", "유효하지 않은 QR 입니다"))).toBe(INVALID_QR);
  });

  it("400 invalid-request(100자 넘는 다른 QR)도 유효하지 않은 QR 로 본다", () => {
    expect(toCheckInErrorMessage(problem(400, "invalid-request", "요청 값이 올바르지 않습니다"))).toBe(INVALID_QR);
  });

  it("403 forbidden 은 이 가게 권한이 없다고 알린다", () => {
    expect(toCheckInErrorMessage(problem(403, "forbidden", "접근 권한이 없습니다"))).toBe(
      "이 가게에서 체크인할 권한이 없어요.",
    );
  });

  it("모르는 type 이면 서버 title 을 그대로 보여 준다", () => {
    expect(toCheckInErrorMessage(problem(500, "internal-error", "서버 오류가 발생했습니다"))).toBe(
      "서버 오류가 발생했습니다",
    );
  });

  it("서버에 닿지 못하면 연결 실패를 알린다", () => {
    expect(toCheckInErrorMessage(new AxiosError("Network Error", "ERR_NETWORK"))).toBe(
      "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  });

  it("axios 에러가 아니면 기본 문구를 쓴다", () => {
    expect(toCheckInErrorMessage(new Error("no linked store"))).toBe(
      "체크인하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  });
});

describe("formatHourMinute", () => {
  it("LocalDateTime 에서 시:분만 꺼낸다(시간대로 다시 해석하지 않는다)", () => {
    expect(formatHourMinute("2026-09-29T12:31:05")).toBe("12:31");
  });
  it("소수 초가 붙어도 시:분만 꺼낸다", () => {
    expect(formatHourMinute("2026-09-29T09:05:00.123456")).toBe("09:05");
  });
  it("모양이 다르면 빈 문자열", () => {
    expect(formatHourMinute("12:31")).toBe("");
  });
});
