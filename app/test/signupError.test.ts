import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import { toSignupError } from "../src/utils/signupError";

const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const problem = (status: number, typeName: string, title = "") => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data: { type: TYPE_BASE + typeName, title, status },
  });
};

describe("toSignupError", () => {
  it("409 duplicate-email 은 1단계로 돌려보낼 종류로 준다", () => {
    expect(toSignupError(problem(409, "duplicate-email"))).toEqual({ kind: "duplicate-email" });
  });

  it.each([
    [400, "unsupported-file-type", "JPG·PNG"],
    [413, "file-too-large", "5MB"],
    [400, "empty-file", "빈 파일"],
    [400, "invalid-file-reference", "올린 파일"],
    [429, "too-many-requests", "잠시 후"],
    [503, "signup-temporarily-closed", "가입 신청을 받을 수 없어요"],
  ])("파일 업로드 %i %s 는 앱 문구로 바꾼다", (status, typeName, expected) => {
    const error = toSignupError(problem(status, typeName, "서버 문구"));
    expect(error.kind).toBe("message");
    expect(error.kind === "message" && error.message).toContain(expected);
  });

  it("모르는 type 이면 서버 title 을 쓴다", () => {
    expect(toSignupError(problem(400, "something-new", "서버 문구"))).toEqual({
      kind: "message",
      message: "서버 문구",
    });
  });
});
