/** 받는 방법. 포장해 가거나 매장에서 먹는다. */
export type OrderReceiveType = "TAKEOUT" | "DINE_IN";

/** 대기(체크인 전)·완료. */
export type OrderStatus = "WAITING" | "DONE";

/** 점주 주문 화면의 주문 한 건. */
export type Order = {
  orderId: number;
  /** 화면에 보이는 주문번호("주문 #1024"). */
  orderNo: number;
  receiveType: OrderReceiveType;
  /** 메뉴 이름을 한 줄로 이어 붙인 것. */
  menuSummary: string;
  /** 주문 시각. 백엔드 LocalDateTime 처럼 시간대 없이 둔다(예: "2026-10-27T12:40:00"). */
  orderedAt: string;
  status: OrderStatus;
};

/** 주문 목록을 담는 react-query 키. 주문 화면과 체크인 결과 화면("조리 시작하기")이 같이 쓴다. */
export const ORDERS_QUERY_KEY = ["orders"];

/** 그 주문을 완료로 바꾼 새 목록. 다른 주문은 그대로 둔다. */
export const markOrderDone = (orders: Order[], orderId: number): Order[] =>
  orders.map((order) => (order.orderId === orderId ? { ...order, status: "DONE" } : order));

/*
  백엔드에 주문 API 가 아직 없어서 피그마 시안의 주문으로 화면을 채운다.
  "조리 시작하기"로 완료한 주문은 앱 안(react-query 캐시)에서만 바뀌고, 앱을 다시 켜면 처음으로 돌아간다.
  TODO(주문 API 가 생기면): 이 목업을 지우고 API 를 부른다.
*/
export const MOCK_ORDERS: Order[] = [
  {
    orderId: 1,
    orderNo: 1024,
    receiveType: "TAKEOUT",
    menuSummary: "후라이드 치킨",
    orderedAt: "2026-10-27T12:40:00",
    status: "WAITING",
  },
  {
    orderId: 2,
    orderNo: 1025,
    receiveType: "DINE_IN",
    menuSummary: "치킨마요 덮밥",
    orderedAt: "2026-10-27T12:46:00",
    status: "WAITING",
  },
  {
    orderId: 3,
    orderNo: 1019,
    receiveType: "DINE_IN",
    menuSummary: "김치볶음밥",
    orderedAt: "2026-10-27T11:52:00",
    status: "DONE",
  },
  {
    orderId: 4,
    orderNo: 1020,
    receiveType: "TAKEOUT",
    menuSummary: "후라이드 치킨",
    orderedAt: "2026-10-27T12:03:00",
    status: "DONE",
  },
  {
    orderId: 5,
    orderNo: 1021,
    receiveType: "TAKEOUT",
    menuSummary: "치킨마요 덮밥",
    orderedAt: "2026-10-27T12:10:00",
    status: "DONE",
  },
  {
    orderId: 6,
    orderNo: 1022,
    receiveType: "DINE_IN",
    menuSummary: "돈까스 정식",
    orderedAt: "2026-10-27T12:21:00",
    status: "DONE",
  },
  {
    orderId: 7,
    orderNo: 1023,
    receiveType: "TAKEOUT",
    menuSummary: "김치볶음밥",
    orderedAt: "2026-10-27T12:32:00",
    status: "DONE",
  },
];
