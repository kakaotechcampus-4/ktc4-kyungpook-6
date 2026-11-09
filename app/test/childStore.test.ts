import { describe, expect, it } from "vitest";
import { toShortAddress } from "../src/services/childStore";

describe("toShortAddress", () => {
  it("맨 앞의 시·도를 뗀다", () => {
    expect(toShortAddress("대구광역시 북구 대학로 80")).toBe("북구 대학로 80");
    expect(toShortAddress("경상북도 경산시 대학로 280")).toBe("경산시 대학로 280");
  });

  it("시·도로 시작하지 않으면 그대로 둔다", () => {
    expect(toShortAddress("북구 대학로 80")).toBe("북구 대학로 80");
  });
});
