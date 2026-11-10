import { describe, expect, it } from "vitest";
import { ROLE_SELECT_PATH, resolveAppRole, splashNextPath } from "../src/appRole";

describe("resolveAppRole", () => {
  it("점주·아동을 읽는다", () => {
    expect(resolveAppRole("OWNER")).toBe("OWNER");
    expect(resolveAppRole("CHILD")).toBe("CHILD");
  });

  it("값이 없으면 역할이 없다 — 웹에서 볼 때다", () => {
    expect(resolveAppRole(undefined)).toBeNull();
    expect(resolveAppRole("")).toBeNull();
  });

  it("모르는 값은 역할로 읽지 않는다", () => {
    expect(resolveAppRole("owner")).toBeNull();
    expect(resolveAppRole("ADMIN")).toBeNull();
  });
});

describe("splashNextPath", () => {
  it("역할이 박혀 있으면 그 역할의 로그인으로 보낸다", () => {
    expect(splashNextPath("OWNER")).toBe("/owner/login");
    expect(splashNextPath("CHILD")).toBe("/child/login");
  });

  it("역할이 없으면 역할 선택으로 보낸다", () => {
    expect(splashNextPath(null)).toBe(ROLE_SELECT_PATH);
  });
});
