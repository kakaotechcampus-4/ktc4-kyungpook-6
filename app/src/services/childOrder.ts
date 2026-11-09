import { postRequest } from "./api";
import type { Order, OrderReceiveType } from "./order";

/** 백엔드 ChildOrderCreateRequest. 메뉴 하나와 받는 방법을 고른다. */
export type ChildOrderRequest = {
  storeId: number;
  menuId: number;
  receiveType: OrderReceiveType;
};

/**
 * 주문하기(POST /api/children/me/orders). 아동 토큰이 필요하다. 주문은 접수 대기(RECEIVED)로 시작한다.
 * 진행 중 주문이 이미 있으면 409 active-order-exists(아이 한 명에 하나뿐), 판매하지 않는 메뉴면 409 menu-not-on-sale.
 */
export const createChildOrder = (payload: ChildOrderRequest) =>
  postRequest<Order>("/api/children/me/orders", payload);
