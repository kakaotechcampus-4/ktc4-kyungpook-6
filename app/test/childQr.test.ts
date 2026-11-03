import { describe, expect, it } from "vitest";
import { MOCK_CHILD_ACTIVE_ORDER, isOrderFinished } from "../src/services/childQr";

const cooking = { ...MOCK_CHILD_ACTIVE_ORDER, status: "COOKING" as const, cookingStartedAt: "2026-11-02T12:00:00" };

describe("isOrderFinished", () => {
  it("조리 시작 뒤 2시간 전까지는 끝나지 않았다", () => {
    expect(isOrderFinished(cooking, new Date(2026, 10, 2, 13, 59))).toBe(false);
  });

  it("조리 시작 뒤 2시간이 지나면 끝난다", () => {
    expect(isOrderFinished(cooking, new Date(2026, 10, 2, 14, 0))).toBe(true);
  });

  it("조리 전 주문은 시간이 지나도 끝나지 않는다", () => {
    expect(isOrderFinished(MOCK_CHILD_ACTIVE_ORDER, new Date(2030, 0, 1))).toBe(false);
  });
});
