import { getRequest, postRequest } from "./api";

/** 받는 방법. 포장해 가거나 매장에서 먹는다. */
export type OrderReceiveType = "TAKEOUT" | "DINE_IN";

/** 주문 상태. RECEIVED(접수 대기) → COOKING(조리 중) → DONE(전달 완료). 접수 대기에서만 CANCELED(취소)가 된다. */
export type OrderStatus = "RECEIVED" | "COOKING" | "DONE" | "CANCELED";

/**
 * 주문 한 건. 백엔드 OrderResponse 그대로다.
 * 시각은 모두 한국 시각이고 시간대가 없다(예: "2026-11-04T12:40:00").
 */
export type Order = {
  /** 주문 ID. 화면의 주문번호("주문 #1024")다. */
  orderId: number;
  storeId: number;
  storeName: string;
  /** 주문 당시 메뉴 이름. */
  menuName: string;
  receiveType: OrderReceiveType;
  status: OrderStatus;
  orderedAt: string;
  /** 조리 시작(방문 확인) 시각. 아직이면 null. */
  cookingStartedAt: string | null;
  /** 전달 완료 시각. 아직이면 null. */
  completedAt: string | null;
  /** 취소 시각. 취소하지 않았으면 null. */
  canceledAt: string | null;
};

/** 점주가 주문에 하는 일. 각각 같은 이름의 API 를 부른다. */
export type OrderAction = "start-cooking" | "complete" | "cancel";

/** 주문 화면의 하위 탭. 완료 탭에는 전달 완료와 취소가 함께 들어간다. */
export type OrderTab = "RECEIVED" | "COOKING" | "DONE";

/** 주문 목록을 담는 react-query 키. 뒤에 가게 ID 를 붙인다. 홈·주문·체크인 결과 화면이 같이 쓴다. */
export const ORDERS_QUERY_KEY = ["orders"];

/** 그 주문이 들어갈 탭. */
export const orderTabOf = (status: OrderStatus): OrderTab => (status === "CANCELED" ? "DONE" : status);

/**
 * 이 가게의 오늘 주문 전부와, 날짜와 상관없이 끝나지 않은 주문(접수 대기·조리 중). 최근 순이다.
 * 점주는 연결된 가게만 볼 수 있다(아니면 403).
 */
export const getStoreOrders = (storeId: number) => getRequest<Order[]>(`/api/stores/${storeId}/orders`);

/**
 * 주문 상태를 바꾼다. 바뀐 주문을 돌려준다.
 *
 * - start-cooking: 접수 대기 → 조리 중. 방문을 확인(QR·직접 확인)한 뒤 "조리 시작하기"로 부른다
 * - complete: 조리 중 → 전달 완료. **누르지 않으면 그 아이는 다음 주문을 할 수 없다**
 * - cancel: 접수 대기 → 취소
 *
 * 지금 상태에서 할 수 없는 일이면 409 `order-status-conflict` 다(다른 기기에서 먼저 처리했거나 아이가 취소함).
 */
export const changeOrderStatus = (storeId: number, orderId: number, action: OrderAction) =>
  postRequest<Order>(`/api/stores/${storeId}/orders/${orderId}/${action}`, undefined);

/** 바뀐 주문 하나를 목록에 넣은 새 목록. 다른 주문은 그대로 둔다. */
export const replaceOrder = (orders: Order[], updated: Order): Order[] =>
  orders.map((order) => (order.orderId === updated.orderId ? updated : order));

/** 목업에서 상태를 바꾼다. 서버가 하듯 상태와 그 시각을 함께 채운다. */
export const applyMockAction = (order: Order, action: OrderAction, at: string): Order => {
  if (action === "start-cooking") return { ...order, status: "COOKING", cookingStartedAt: at };
  if (action === "complete") return { ...order, status: "DONE", completedAt: at };
  return { ...order, status: "CANCELED", canceledAt: at };
};

const mockOrder = (
  orderId: number,
  receiveType: OrderReceiveType,
  menuName: string,
  orderedAt: string,
  status: OrderStatus,
  changedAt: string | null = null,
): Order => ({
  orderId,
  storeId: 0,
  storeName: "맛나 치킨",
  menuName,
  receiveType,
  status,
  orderedAt,
  cookingStartedAt: status === "COOKING" || status === "DONE" ? changedAt : null,
  completedAt: status === "DONE" ? changedAt : null,
  canceledAt: status === "CANCELED" ? changedAt : null,
});

/*
  VITE_STORE_MOCK=true 로 백엔드 없이 화면을 볼 때 쓰는 주문. 피그마 시안의 주문이다.
  상태를 바꾸면 앱 안(react-query 캐시)에서만 바뀌고, 앱을 다시 켜면 처음으로 돌아간다.
*/
export const MOCK_ORDERS: Order[] = [
  mockOrder(1025, "DINE_IN", "치킨마요 덮밥", "2026-10-27T12:46:00", "RECEIVED"),
  mockOrder(1024, "TAKEOUT", "후라이드 치킨", "2026-10-27T12:40:00", "RECEIVED"),
  mockOrder(1023, "TAKEOUT", "김치볶음밥", "2026-10-27T12:32:00", "COOKING", "2026-10-27T12:35:00"),
  mockOrder(1022, "DINE_IN", "돈까스 정식", "2026-10-27T12:21:00", "DONE", "2026-10-27T12:59:00"),
  mockOrder(1021, "TAKEOUT", "치킨마요 덮밥", "2026-10-27T12:10:00", "DONE", "2026-10-27T12:55:00"),
  mockOrder(1020, "TAKEOUT", "후라이드 치킨", "2026-10-27T12:03:00", "DONE", "2026-10-27T12:30:00"),
  mockOrder(1019, "DINE_IN", "김치볶음밥", "2026-10-27T11:52:00", "CANCELED", "2026-10-27T11:52:00"),
];
