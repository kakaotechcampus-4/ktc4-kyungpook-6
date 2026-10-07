import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import { toLoginErrorMessage } from "../src/utils/loginError";

/*
  백엔드가 실제로 보내는 에러 본문 모양(docs/에러_처리_가이드.md)으로 만든다.
  type 베이스 URI 와 title 은 ErrorCode.java 값 그대로다.
*/
const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const problem = (status: number, typeName: string, title: string, extra: object = {}) => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data: { type: TYPE_BASE + typeName, title, status, instance: "/api/auth/login", ...extra },
  });
};

describe("toLoginErrorMessage", () => {
  it("401 invalid-credentials 는 아이디·비밀번호가 맞지 않다고 알린다", () => {
    const error = problem(401, "invalid-credentials", "이메일 또는 비밀번호가 올바르지 않습니다");
    expect(toLoginErrorMessage(error)).toBe("아이디 또는 비밀번호가 맞지 않아요.");
  });

  it("403 owner-pending-approval 은 승인 대기를 알린다", () => {
    const error = problem(403, "owner-pending-approval", "관리자 승인을 기다리는 계정입니다");
    expect(toLoginErrorMessage(error)).toBe("관리자 승인을 기다리고 있어요. 승인되면 로그인할 수 있어요.");
  });

  it("403 owner-rejected 는 승인되지 않은 계정이라고 알린다", () => {
    const error = problem(403, "owner-rejected", "가입이 거절된 계정입니다");
    expect(toLoginErrorMessage(error)).toBe("가입이 승인되지 않은 계정이에요.");
  });

  it("400 invalid-request 에서 email 칸이 틀리면 이메일 형식을 안내한다", () => {
    const error = problem(400, "invalid-request", "요청 값이 올바르지 않습니다", {
      errors: [{ field: "email", message: "이메일 형식이 올바르지 않습니다" }],
    });
    expect(toLoginErrorMessage(error)).toBe("아이디는 이메일 형식으로 입력해 주세요.");
  });

  it("400 invalid-request 에서 password 칸이 틀리면 비밀번호 입력을 안내한다", () => {
    const error = problem(400, "invalid-request", "요청 값이 올바르지 않습니다", {
      errors: [{ field: "password", message: "비밀번호를 입력해 주세요" }],
    });
    expect(toLoginErrorMessage(error)).toBe("비밀번호를 입력해 주세요.");
  });

  it("모르는 type 이면 서버 title 을 그대로 보여 준다", () => {
    const error = problem(500, "internal-error", "서버 오류가 발생했습니다");
    expect(toLoginErrorMessage(error)).toBe("서버 오류가 발생했습니다");
  });

  it("서버에 닿지 못하면 연결 실패를 알린다", () => {
    const error = new AxiosError("Network Error", "ERR_NETWORK");
    expect(toLoginErrorMessage(error)).toBe("서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
  });

  it("type·title 이 없는 응답(필터 단계 에러)이면 기본 문구를 쓴다", () => {
    const config = { headers: new AxiosHeaders() };
    const error = new AxiosError("Request failed", "ERR_BAD_RESPONSE", config, null, {
      status: 500, statusText: "", headers: {}, config,
      data: { timestamp: "2026-10-07T00:00:00Z", status: 500, error: "Internal Server Error", path: "/api/auth/login" },
    });
    expect(toLoginErrorMessage(error)).toBe("로그인하지 못했어요. 잠시 후 다시 시도해 주세요.");
  });
});
