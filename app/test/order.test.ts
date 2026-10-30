import { describe, expect, it } from "vitest";
import type { Order } from "../src/services/order";
import { markOrderDone } from "../src/services/order";

const order = (orderId: number, status: Order["status"]): Order => ({
  orderId,
  orderNo: 1000 + orderId,
  receiveType: "TAKEOUT",
  menuSummary: "후라이드 치킨",
  orderedAt: "2026-10-27T12:40:00",
  status,
});

describe("markOrderDone", () => {
  it("고른 주문만 완료로 바꾼다", () => {
    const orders = [order(1, "WAITING"), order(2, "WAITING"), order(3, "DONE")];

    expect(markOrderDone(orders, 1).map((o) => o.status)).toEqual(["DONE", "WAITING", "DONE"]);
  });

  it("원래 목록은 고치지 않는다", () => {
    const orders = [order(1, "WAITING")];

    markOrderDone(orders, 1);

    expect(orders[0].status).toBe("WAITING");
  });

  it("없는 주문이면 그대로 둔다", () => {
    const orders = [order(1, "WAITING")];

    expect(markOrderDone(orders, 99)).toEqual(orders);
  });
});
