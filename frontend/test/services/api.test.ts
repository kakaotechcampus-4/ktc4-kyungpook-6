import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AxiosAdapter, InternalAxiosRequestConfig } from "axios";
import { customInstance, LOGIN_PATH } from "../../src/services/api";
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

/* 401 을 돌려주는 가짜 서버. */
const unauthorizedAdapter: AxiosAdapter = async (config) => {
  throw Object.assign(new Error("Unauthorized"), {
    isAxiosError: true,
    config,
    response: { data: { title: "로그인이 필요합니다" }, status: 401, statusText: "Unauthorized", headers: {}, config },
    toJSON: () => ({}),
  });
};

/**
 * 응답 인터셉터 — 로그인이 풀리면(401) 로그인 화면으로 보낸다.
 *
 * <p>이게 없으면 가게 목록이 조회 실패를 **목업 데이터**로 덮어, 로그인이 풀린 담당자가
 * 진짜처럼 보이는 가짜 목록을 읽게 된다(PROMPT-150). 권한 검사 스위치가 켜지면 더 자주 걸린다.
 */
describe("응답 인터셉터 — 401 처리", () => {
  let replaced: string[] = [];

  beforeEach(() => {
    storage.clear();
    replaced = [];
    vi.stubGlobal("sessionStorage", fakeSessionStorage);
    vi.stubGlobal("window", {
      location: { pathname: "/", replace: (url: string) => replaced.push(url) },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("401 이면 토큰을 지우고 로그인 화면으로 보낸다", async () => {
    storage.set(ACCESS_TOKEN_KEY, "expired");

    await expect(
      customInstance({ url: "/api/stores", method: "GET", adapter: unauthorizedAdapter })
    ).rejects.toThrow();

    expect(storage.has(ACCESS_TOKEN_KEY)).toBe(false);
    expect(replaced).toEqual([LOGIN_PATH]);
  });

  it("로그인 요청의 401(비밀번호 틀림)은 가로채지 않는다 — 로그인 화면이 문구로 알려 줄 일이다", async () => {
    await expect(
      customInstance({ url: "/api/auth/login", method: "POST", adapter: unauthorizedAdapter })
    ).rejects.toThrow();

    expect(replaced).toEqual([]);
  });

  it("이미 로그인 화면이면 다시 보내지 않는다", async () => {
    vi.stubGlobal("window", {
      location: { pathname: LOGIN_PATH, replace: (url: string) => replaced.push(url) },
    });

    await expect(
      customInstance({ url: "/api/stores", method: "GET", adapter: unauthorizedAdapter })
    ).rejects.toThrow();

    expect(replaced).toEqual([]);
  });

  it("401 이 아닌 실패는 그대로 흘려보낸다 — 서버가 잠깐 죽은 것과 로그인이 풀린 것은 다르다", async () => {
    const serverErrorAdapter: AxiosAdapter = async (config) => {
      throw Object.assign(new Error("Server Error"), {
        isAxiosError: true,
        config,
        response: { data: null, status: 500, statusText: "Server Error", headers: {}, config },
        toJSON: () => ({}),
      });
    };

    await expect(
      customInstance({ url: "/api/stores", method: "GET", adapter: serverErrorAdapter })
    ).rejects.toThrow();

    expect(replaced).toEqual([]);
  });
});

/**
 * 응답이 오지 않는 백엔드에 영영 매달리지 않는다 — PROMPT-160.
 *
 * <p>번들에 옛 서버 주소가 박혀 있을 때 그 주소가 연결을 거절조차 하지 않으면 요청이 pending
 * 인 채로 끝나지 않는다. 실패가 나지 않으니 401 리다이렉트도 목업 폴백도 돌지 못하고 화면은
 * 스켈레톤만 계속 돈다. 운영에서 실제로 그랬다.
 */
describe("요청 타임아웃", () => {
  beforeEach(() => {
    storage.clear();
    sent = undefined;
    vi.stubGlobal("sessionStorage", fakeSessionStorage);
  });

  it("모든 요청에 타임아웃이 걸린다 — 끝나지 않는 요청은 실패로 떨어져야 폴백이 돈다", async () => {
    await customInstance({ url: "/api/stores", method: "GET", adapter: captureAdapter });

    expect(sent?.timeout).toBeGreaterThan(0);
  });
});
