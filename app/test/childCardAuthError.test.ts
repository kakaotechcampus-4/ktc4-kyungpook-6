import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import { toChildCardAuthErrorMessage } from "../src/utils/childCardAuthError";

const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const problem = (status: number, typeName: string) => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data: { type: TYPE_BASE + typeName, title: "", status, instance: "/api/auth/children/card" },
  });
};

describe("toChildCardAuthErrorMessage", () => {
  it("429 too-many-requests 는 잠시 뒤 다시 하라고 알린다", () => {
    expect(toChildCardAuthErrorMessage(problem(429, "too-many-requests"))).toBe(
      "너무 여러 번 시도했어요. 잠시 후 다시 해 주세요.",
    );
  });

  it("503 card-auth-unavailable 은 지금 인증할 수 없다고 알린다", () => {
    expect(toChildCardAuthErrorMessage(problem(503, "card-auth-unavailable"))).toBe(
      "지금은 카드 인증을 할 수 없어요. 잠시 후 다시 해 주세요.",
    );
  });

  it("서버에 닿지 못하면 연결 문구를 쓴다", () => {
    const error = new AxiosError("Network Error", "ERR_NETWORK");
    expect(toChildCardAuthErrorMessage(error)).toBe("서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
  });

  it("모르는 type 이면 기본 문구를 쓴다", () => {
    expect(toChildCardAuthErrorMessage(problem(500, "internal-error"))).toBe(
      "인증하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  });
});
