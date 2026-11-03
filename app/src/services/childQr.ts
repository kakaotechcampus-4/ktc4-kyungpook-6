import type { OrderReceiveType } from "./order";

/** 아동 주문의 진행 단계. 점주가 체크인 결과 화면에서 "조리 시작하기"를 누르면 COOKING 이 된다. */
export type ChildOrderStatus = "RECEIVED" | "COOKING";

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

/** 조리 시작 뒤 이 시간(ms)이 지나면 주문이 끝난 것으로 보고 내 QR 을 비운다(피드백 창이 닫힌다). */
export const FEEDBACK_WINDOW_MS = 2 * 60 * 60 * 1000;

/** 진행 중인 주문을 담는 react-query 키. 주문 취소하면 null 로 바꾼다. */
export const CHILD_ACTIVE_ORDER_QUERY_KEY = ["child-active-order"];

/*
  백엔드에 아동 주문 API 가 아직 없어서 피그마 시안의 주문으로 화면을 채운다.
  주문 취소는 앱 안(react-query 캐시)에서만 바뀌고, 앱을 다시 켜면 처음으로 돌아간다.
  조리 시작 뒤 화면을 보려면 status 를 "COOKING" 으로, cookingStartedAt 을 2시간 안의 시각으로 바꾼다.
  TODO(주문 API 가 생기면): 이 목업을 지우고 API 를 부른다.
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

/** 조리 시작 뒤 2시간이 지나 끝난 주문인지. */
export const isOrderFinished = (order: ChildActiveOrder, at: Date) =>
  order.status === "COOKING" &&
  order.cookingStartedAt !== null &&
  at.getTime() - new Date(order.cookingStartedAt).getTime() >= FEEDBACK_WINDOW_MS;
