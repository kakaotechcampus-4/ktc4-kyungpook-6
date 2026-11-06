import { isAxiosError } from "axios";

/*
  가게 주문 API(/api/stores/{storeId}/orders/...)가 줄 수 있는 에러와 화면 문구.
  백엔드 StoreOrderController 와 docs/에러_처리_가이드.md 의 type 목록에 맞췄다.
  401 은 api.ts 인터셉터가 로그인 화면으로 보내서 여기까지 오지 않는다.
*/
const ORDER_ERROR_MESSAGES: Record<string, string> = {
  // 409. 지금 상태에서 할 수 없는 일 — 다른 기기에서 먼저 처리했거나 아이가 취소했다. 목록은 다시 불러온다.
  "order-status-conflict": "이미 처리된 주문이에요. 목록을 새로 불러왔어요.",
  // 404. 이 가게의 주문이 아니다.
  "order-not-found": "주문을 찾을 수 없어요.",
  // 403. 이 계정에 연결되지 않은 가게다.
  forbidden: "이 가게의 주문을 처리할 권한이 없어요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "주문을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 주문 처리(조리 시작·완료·취소) 실패를 화면 문구로 바꾼다.
 *
 * 에러 처리 가이드대로 type 으로 나눈다. 모르는 type 이면 서버 title 을,
 * 서버에 닿지 못했거나 title 도 없으면 기본 문구를 쓴다.
 */
export function toOrderErrorMessage(error: unknown): string {
  if (!isAxiosError(error)) return DEFAULT_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;

  const { type, title } = (error.response.data ?? {}) as { type?: unknown; title?: unknown };
  const typeName = typeof type === "string" ? type.split("/").pop() : undefined;

  if (typeName && ORDER_ERROR_MESSAGES[typeName]) return ORDER_ERROR_MESSAGES[typeName];
  if (typeof title === "string" && title) return title;
  return DEFAULT_MESSAGE;
}
