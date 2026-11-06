import { describe, expect, it } from "vitest";
import type { Order } from "../src/services/order";
import { applyMockAction, orderTabOf, replaceOrder, sortByFinishedDesc } from "../src/services/order";

const order = (orderId: number, status: Order["status"]): Order => ({
  orderId,
  storeId: 10,
  storeName: "맛나 치킨",
  menuName: "후라이드 치킨",
  receiveType: "TAKEOUT",
  status,
  orderedAt: "2026-11-04T12:40:00",
  cookingStartedAt: null,
  completedAt: null,
  canceledAt: null,
});

describe("orderTabOf", () => {
  it("취소한 주문은 완료 탭에 넣는다", () => {
    expect(orderTabOf("CANCELED")).toBe("DONE");
  });

  it("나머지는 상태와 같은 탭이다", () => {
    expect(["RECEIVED", "COOKING", "DONE"].map((s) => orderTabOf(s as Order["status"]))).toEqual([
      "RECEIVED",
      "COOKING",
      "DONE",
    ]);
  });
});

describe("replaceOrder", () => {
  it("같은 주문만 바뀐 주문으로 바꾼다", () => {
    const orders = [order(1, "RECEIVED"), order(2, "RECEIVED")];

    expect(replaceOrder(orders, order(1, "COOKING")).map((o) => o.status)).toEqual(["COOKING", "RECEIVED"]);
  });

  it("원래 목록은 고치지 않는다", () => {
    const orders = [order(1, "RECEIVED")];

    replaceOrder(orders, order(1, "COOKING"));

    expect(orders[0].status).toBe("RECEIVED");
  });

  it("없는 주문이면 그대로 둔다", () => {
    const orders = [order(1, "RECEIVED")];

    expect(replaceOrder(orders, order(99, "DONE"))).toEqual(orders);
  });
});

describe("applyMockAction", () => {
  const at = "2026-11-04T12:55:00";

  it("조리 시작은 조리 중으로 바꾸고 시작 시각을 채운다", () => {
    expect(applyMockAction(order(1, "RECEIVED"), "start-cooking", at)).toMatchObject({
      status: "COOKING",
      cookingStartedAt: at,
    });
  });

  it("조리 완료는 전달 완료로 바꾸고 완료 시각을 채운다", () => {
    expect(applyMockAction(order(1, "COOKING"), "complete", at)).toMatchObject({ status: "DONE", completedAt: at });
  });

  it("취소는 취소로 바꾸고 취소 시각을 채운다", () => {
    expect(applyMockAction(order(1, "RECEIVED"), "cancel", at)).toMatchObject({ status: "CANCELED", canceledAt: at });
  });
});

describe("sortByFinishedDesc", () => {
  it("늦게 들어온 주문을 먼저 취소하고 일찍 들어온 주문을 나중에 완료하면, 완료한 주문이 위에 온다", () => {
    const canceled = applyMockAction(order(1025, "RECEIVED"), "cancel", "2026-10-27T13:00:00");
    const done = applyMockAction(order(1023, "COOKING"), "complete", "2026-10-27T13:05:00");

    expect(sortByFinishedDesc([canceled, done]).map((o) => o.orderId)).toEqual([1023, 1025]);
  });
});
