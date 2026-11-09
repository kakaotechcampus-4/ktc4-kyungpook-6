import { getRequest, postRequest } from "./api";
import type { Order, OrderReceiveType } from "./order";

/**
 * 아동 주문의 진행 단계. 점주 앱과 같은 세 단계다.
 * 점주가 체크인 결과 화면에서 "조리 시작하기"를 누르면 COOKING, "조리 완료"를 누르면 DONE.
 */
export type ChildOrderStatus = "RECEIVED" | "COOKING" | "DONE";

/** 내 QR 화면에 보이는 진행 중인 주문. */
export type ChildActiveOrder = {
  orderId: number;
  /** 화면에 보이는 주문번호("주문 #1024"). */
  orderNo: number;
  storeName: string;
  menuName: string;
  receiveType: OrderReceiveType;
  status: ChildOrderStatus;
  /** 조리를 시작한 시각. 백엔드 LocalDateTime 처럼 시간대 없이 둔다. 조리 전이면 null. */
  cookingStartedAt: string | null;
};

/** 진행 중인 주문을 담는 react-query 키. 주문 취소하면 null 로 바꾼다. */
export const CHILD_ACTIVE_ORDER_QUERY_KEY = ["child-active-order"];

/*
  VITE_STORE_MOCK=true 로 백엔드 없이 화면을 볼 때 쓰는 주문. 피그마 시안의 주문이다.
  주문 취소는 앱 안(react-query 캐시)에서만 바뀌고, 앱을 다시 켜면 처음으로 돌아간다.
  조리 중·조리 완료 화면을 보려면 status 를 "COOKING"·"DONE" 으로, cookingStartedAt 을 아무 시각으로 바꾼다.
*/
export const MOCK_CHILD_ACTIVE_ORDER: ChildActiveOrder = {
  orderId: 1,
  orderNo: 1024,
  storeName: "맛나 치킨",
  menuName: "후라이드 치킨",
  receiveType: "TAKEOUT",
  status: "RECEIVED",
  cookingStartedAt: null,
};


/** 서버 주문을 내 QR 화면의 주문으로. 진행 중 주문만 오니 상태는 접수 대기·조리 중 둘뿐이다. */
const toChildActiveOrder = (order: Order): ChildActiveOrder => ({
  orderId: order.orderId,
  orderNo: order.orderId,
  storeName: order.storeName,
  menuName: order.menuName,
  receiveType: order.receiveType,
  status: order.status === "COOKING" ? "COOKING" : "RECEIVED",
  cookingStartedAt: order.cookingStartedAt,
});

/**
 * 진행 중 주문(GET /api/children/me/orders/active). 접수 대기·조리 중인 내 주문, 없으면 null.
 * 점주가 조리를 완료하거나 취소되면 진행 중 주문이 아니다.
 */
export const getActiveChildOrder = async (): Promise<ChildActiveOrder | null> => {
  const { order } = await getRequest<{ order: Order | null }>("/api/children/me/orders/active");
  return order ? toChildActiveOrder(order) : null;
};

/** 주문 취소(POST /api/children/me/orders/{orderId}/cancel). 접수 대기일 때만 된다 — 아니면 409 order-status-conflict. */
export const cancelChildOrder = (orderId: number) =>
  postRequest<Order>(`/api/children/me/orders/${orderId}/cancel`, undefined);

/**
 * 진행 중 주문을 다시 읽은 값에 지난 값을 맞춘다. 서버는 조리 완료된 주문을 진행 중 주문으로 주지 않아(null),
 * 조리 중이던 주문이 사라지면 그때 한 번 조리 완료로 보여 준다 — 점주는 조리 중인 주문을 취소할 수 없어 사라졌다면 조리를 끝낸 것이다.
 * 그 뒤 다시 불러오면(화면에 다시 들어오거나 앱을 다시 켜면) 서버대로 비운다. 접수 대기였던 주문이 사라지면 취소된 것이라 비운다.
 * 시간으로 끝내는 규칙은 두지 않는다 — 서버가 주는 동안은 계속 보여 줘야 새 주문이 409 로 막히는 이유를 아이가 안다.
 */
export const reconcileActiveOrder = (
  previous: ChildActiveOrder | null | undefined,
  next: ChildActiveOrder | null,
): ChildActiveOrder | null => {
  if (next) return next;
  if (previous?.status === "COOKING") return { ...previous, status: "DONE" };
  return null;
};
