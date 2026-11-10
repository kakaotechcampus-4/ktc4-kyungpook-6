import { describe, expect, it } from "vitest";
import { decideSplashPath } from "../src/hooks/splash";

/*
  스플래시 뒤 어디로 가는가. 웹판은 아동·점주가 한 도메인이라 저장된 로그인을 같이 본다 —
  점주로 로그인한 브라우저로 /app/child 를 열면 아동 앱이 점주 홈으로 튀었다(운영 2026-11-11).
*/
const NEXT = "/child/login";

describe("decideSplashPath — 역할이 박힌 빌드", () => {
  it("같은 역할의 로그인이면 그 홈으로 간다", () => {
    expect(decideSplashPath("CHILD", null, "CHILD", NEXT)).toEqual({ path: "/child/home", discard: null });
    expect(decideSplashPath("OWNER", null, "OWNER", "/owner/login")).toEqual({ path: "/home", discard: null });
  });

  it("다른 역할의 로그인은 버리고 로그인 화면으로 간다", () => {
    expect(decideSplashPath("CHILD", null, "OWNER", NEXT)).toEqual({ path: NEXT, discard: "auth" });
    expect(decideSplashPath("OWNER", null, "CHILD", "/owner/login")).toEqual({
      path: "/owner/login",
      discard: "auth",
    });
  });

  it("점주 가입 상태 토큰은 점주 빌드에서만 가입 완료 화면으로 보낸다", () => {
    expect(decideSplashPath("OWNER", "status-token", null, "/owner/login")).toEqual({
      path: "/owner/signup/complete",
      discard: null,
    });
    expect(decideSplashPath("CHILD", "status-token", null, NEXT)).toEqual({ path: NEXT, discard: "status" });
  });

  it("아무것도 없으면 nextPath", () => {
    expect(decideSplashPath("CHILD", null, null, NEXT)).toEqual({ path: NEXT, discard: null });
  });
});

describe("decideSplashPath — 역할이 없는 빌드(웹에서 볼 때)", () => {
  it("저장된 로그인의 역할을 그대로 따른다 — 전과 같다", () => {
    expect(decideSplashPath(null, null, "OWNER", "/role-select")).toEqual({ path: "/home", discard: null });
    expect(decideSplashPath(null, null, "CHILD", "/role-select")).toEqual({ path: "/child/home", discard: null });
    expect(decideSplashPath(null, "status-token", null, "/role-select")).toEqual({
      path: "/owner/signup/complete",
      discard: null,
    });
    expect(decideSplashPath(null, null, null, "/role-select")).toEqual({ path: "/role-select", discard: null });
  });
});
