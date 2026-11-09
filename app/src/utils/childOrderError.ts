import { isAxiosError } from "axios";

/*
  아동 주문(POST /api/children/me/orders)이 줄 수 있는 에러와 화면 문구.
  백엔드 ChildOrderController·OrderService·ErrorCode 에 맞췄다.
*/
const CHILD_ORDER_ERROR_MESSAGES: Record<string, string> = {
  // 409. 아이 한 명에게 진행 중 주문은 하나뿐이다. 버튼을 두 번 눌러도 온다.
  "active-order-exists": "이미 진행 중인 주문이 있어요. 내 QR에서 확인해 주세요.",
  // 409. 오늘 판매하지 않는 메뉴.
  "menu-not-on-sale": "오늘은 주문할 수 없는 메뉴예요. 다른 메뉴를 골라 주세요.",
  // 404. 그 가게의 메뉴가 아니거나 지운 메뉴.
  "menu-not-found": "메뉴를 찾을 수 없어요. 가게 정보를 다시 확인해 주세요.",
  // 409. 점주가 연결되지 않은 가게.
  "store-not-orderable": "지금은 주문을 받지 않는 가게예요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "주문하지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 에러 type 의 마지막 이름("active-order-exists"). 없으면 undefined. */
export const toChildOrderErrorType = (error: unknown) => {
  if (!isAxiosError(error)) return undefined;
  const { type } = (error.response?.data ?? {}) as { type?: unknown };
  return typeof type === "string" ? type.split("/").pop() : undefined;
};

/** 주문 실패를 화면 문구로 바꾼다. 모르는 type 이면 기본 문구를 쓴다. */
export function toChildOrderErrorMessage(error: unknown): string {
  if (isAxiosError(error) && !error.response) return NETWORK_ERROR_MESSAGE;
  const typeName = toChildOrderErrorType(error);
  return (typeName && CHILD_ORDER_ERROR_MESSAGES[typeName]) || DEFAULT_MESSAGE;
}
