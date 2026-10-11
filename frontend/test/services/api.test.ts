import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
import { customInstance } from "../../src/services/api";
import { ACCESS_TOKEN_KEY } from "../../src/services/authToken";

/* 테스트는 node 환경이라 sessionStorage 가 없다. Map 으로 흉내 낸다. */
const storage = new Map<string, string>();
const fakeSessionStorage = {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => storage.set(key, value),
  removeItem: (key: string) => storage.delete(key),
};

/* 실제로 보내지 않고, 인터셉터를 거친 뒤의 요청 설정만 받아 둔다. */
let sent: InternalAxiosRequestConfig | undefined;
const captureAdapter: AxiosAdapter = async (config) => {
  sent = config;
  return { data: null, status: 200, statusText: "OK", headers: {}, config };
};

/**
 * 요청 인터셉터 — 세션 스토리지의 accessToken 을 Authorization 헤더로 싣는다.
 *
 * <p>Orval 이 만든 함수는 모두 customInstance 를 거치므로 그걸로 확인한다.
 */
describe("요청 인터셉터 — Authorization 헤더", () => {
  beforeEach(() => {
    storage.clear();
    sent = undefined;
    vi.stubGlobal("sessionStorage", fakeSessionStorage);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("토큰이 있으면 Bearer 로 싣는다", async () => {
    storage.set(ACCESS_TOKEN_KEY, "token-123");

    await customInstance({ url: "/api/stores", method: "GET", adapter: captureAdapter });

    expect(sent?.headers.get("Authorization")).toBe("Bearer token-123");
  });

  it("토큰이 없으면 헤더를 붙이지 않는다", async () => {
    await customInstance({ url: "/api/auth/login", method: "POST", adapter: captureAdapter });

    expect(sent?.headers.has("Authorization")).toBe(false);
  });

  it("요청마다 다시 읽는다 — 로그인 직후 요청에도 새 토큰이 실린다", async () => {
    await customInstance({ url: "/api/stores", method: "GET", adapter: captureAdapter });
    expect(sent?.headers.has("Authorization")).toBe(false);

    storage.set(ACCESS_TOKEN_KEY, "fresh");
    await customInstance({ url: "/api/stores", method: "GET", adapter: captureAdapter });

    expect(sent?.headers.get("Authorization")).toBe("Bearer fresh");
  });

  it("호출부가 직접 넣은 Authorization 은 덮어쓰지 않는다", async () => {
    storage.set(ACCESS_TOKEN_KEY, "token-123");

    await customInstance({
      url: "/api/stores",
      method: "GET",
      headers: { Authorization: "Bearer other" },
      adapter: captureAdapter,
    });

    expect(sent?.headers.get("Authorization")).toBe("Bearer other");
  });
});
