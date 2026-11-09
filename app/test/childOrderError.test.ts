import { AxiosError, AxiosHeaders } from "axios";
import { describe, expect, it } from "vitest";
import { toChildOrderErrorMessage } from "../src/utils/childOrderError";

const TYPE_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

const problem = (status: number, typeName: string) => {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError("Request failed", "ERR_BAD_REQUEST", config, null, {
    status,
    statusText: "",
    headers: {},
    config,
    data: { type: TYPE_BASE + typeName, title: "", status, instance: "/api/children/me/orders" },
  });
};

describe("toChildOrderErrorMessage", () => {
  it("409 active-order-exists 는 내 QR 에서 확인하라고 알린다", () => {
    expect(toChildOrderErrorMessage(problem(409, "active-order-exists"))).toBe(
      "이미 진행 중인 주문이 있어요. 내 QR에서 확인해 주세요.",
    );
  });

  it("409 menu-not-on-sale 은 다른 메뉴를 고르라고 알린다", () => {
    expect(toChildOrderErrorMessage(problem(409, "menu-not-on-sale"))).toBe(
      "오늘은 주문할 수 없는 메뉴예요. 다른 메뉴를 골라 주세요.",
    );
  });

  it("서버에 닿지 못하면 연결 문구를 쓴다", () => {
    expect(toChildOrderErrorMessage(new AxiosError("Network Error", "ERR_NETWORK"))).toBe(
      "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  });
});
