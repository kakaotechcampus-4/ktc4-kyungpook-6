import { describe, expect, it } from "vitest";
import { AxiosError, AxiosHeaders } from "axios";
import type { AxiosResponse } from "axios";
import { toLoginErrorMessage } from "../../src/hooks/login";

const ERROR_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

function problem(status: number, data: unknown) {
  const config = { headers: new AxiosHeaders() };
  const response = { status, statusText: "", headers: {}, config, data } as AxiosResponse;
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, undefined, response);
}

/** 로그인 실패 문구 — docs/에러_처리_가이드.md "프론트가 읽는 법" 을 따른다. */
describe("toLoginErrorMessage", () => {
  it("본문 검증 실패면 필드 메시지를 보여준다", () => {
    const error = problem(400, {
      type: `${ERROR_BASE}invalid-request`,
      title: "요청 값이 올바르지 않습니다",
      errors: [{ field: "email", message: "이메일 형식이 올바르지 않습니다" }],
    });

    expect(toLoginErrorMessage(error)).toBe("이메일 형식이 올바르지 않습니다");
  });

  it("필드 오류가 없으면 title 을 보여준다", () => {
    const error = problem(401, {
      type: `${ERROR_BASE}invalid-credentials`,
      title: "이메일 또는 비밀번호가 올바르지 않습니다",
    });

    expect(toLoginErrorMessage(error)).toBe("이메일 또는 비밀번호가 올바르지 않습니다");
  });

  it("응답 본문이 없으면(네트워크 오류 등) 기본 문구를 보여준다", () => {
    expect(toLoginErrorMessage(new Error("Network Error"))).toBe(
      "로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."
    );
  });
});
