import { isAxiosError } from "axios";

/*
  체크인 API(POST /api/stores/{storeId}/check-ins)가 줄 수 있는 에러와 화면 문구.
  백엔드 CheckInController·CheckInRequest 와 docs/에러_처리_가이드.md 의 type 목록에 맞췄다.
  401 은 api.ts 인터셉터가 로그인 화면으로 보내서 여기까지 오지 않는다.
*/
const INVALID_QR_MESSAGE = "유효하지 않은 QR이에요. 다시 확인해주세요.";

const CHECK_IN_ERROR_MESSAGES: Record<string, string> = {
  // 400. 발급한 적 없음·재발급된 옛 QR·형식 오류를 서버가 일부러 구분하지 않는다.
  "invalid-qr-token": INVALID_QR_MESSAGE,
  // 400. qrPayload 가 100자를 넘는 경우 — 우리 QR 이 아닌 다른 QR(긴 주소 등)을 찍었을 때다.
  "invalid-request": INVALID_QR_MESSAGE,
  // 403. 이 계정에 연결되지 않은 가게로 불렀다.
  forbidden: "이 가게에서 체크인할 권한이 없어요.",
  // 404. 관리자 계정으로 없는 가게에 불렀을 때만 온다.
  "store-not-found": "가게 정보를 찾을 수 없어요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "체크인하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 체크인 실패를 화면 문구로 바꾼다.
 *
 * 에러 처리 가이드대로 type 으로 나눈다. 모르는 type 이면 서버 title 을,
 * 서버에 닿지 못했거나 title 도 없으면 기본 문구를 쓴다.
 */
export function toCheckInErrorMessage(error: unknown): string {
  if (!isAxiosError(error)) return DEFAULT_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;

  const { type, title } = (error.response.data ?? {}) as { type?: unknown; title?: unknown };
  const typeName = typeof type === "string" ? type.split("/").pop() : undefined;

  if (typeName && CHECK_IN_ERROR_MESSAGES[typeName]) return CHECK_IN_ERROR_MESSAGES[typeName];
  if (typeof title === "string" && title) return title;
  return DEFAULT_MESSAGE;
}
