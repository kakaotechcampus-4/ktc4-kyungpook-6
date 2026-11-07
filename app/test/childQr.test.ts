import { describe, expect, it } from "vitest";
import { MOCK_CHILD_ACTIVE_ORDER, reconcileActiveOrder } from "../src/services/childQr";

const cooking = { ...MOCK_CHILD_ACTIVE_ORDER, status: "COOKING" as const, cookingStartedAt: "2026-11-02T12:00:00" };

describe("reconcileActiveOrder", () => {
  it("조리 중이던 주문이 사라지면 조리 완료로 남긴다", () => {
    expect(reconcileActiveOrder(cooking, null)?.status).toBe("DONE");
  });

  it("접수 대기였던 주문이 사라지면(취소) 비운다", () => {
    expect(reconcileActiveOrder(MOCK_CHILD_ACTIVE_ORDER, null)).toBeNull();
  });

  it("조리 완료로 보여 준 뒤 다시 불러오면 비운다", () => {
    expect(reconcileActiveOrder({ ...cooking, status: "DONE" }, null)).toBeNull();
  });

  it("서버에 주문이 있으면 그 주문을 쓴다", () => {
    expect(reconcileActiveOrder(MOCK_CHILD_ACTIVE_ORDER, cooking)).toBe(cooking);
  });
});
