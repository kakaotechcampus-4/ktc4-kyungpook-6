import { isAxiosError } from "axios";

/*
  아동 카드 인증(POST /api/auth/children/card)이 줄 수 있는 에러와 화면 문구.
  백엔드 ChildAuthController·ErrorCode 와 docs/에러_처리_가이드.md 의 type 목록에 맞췄다.
*/
const CARD_AUTH_ERROR_MESSAGES: Record<string, string> = {
  // 400. 숫자 16자리가 아니다. 앱이 16자리를 다 채워야 보내니 보통은 오지 않는다.
  "invalid-request": "카드 번호 16자리를 확인해 주세요.",
  // 429. 같은 곳에서 10분에 20번·하루 100번을 넘었다.
  "too-many-requests": "너무 여러 번 시도했어요. 잠시 후 다시 해 주세요.",
  // 503. 서버에 카드 인증 설정이 없다.
  "card-auth-unavailable": "지금은 카드 인증을 할 수 없어요. 잠시 후 다시 해 주세요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "인증하지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 카드 인증 실패를 화면 문구로 바꾼다. 모르는 type 이면 기본 문구를 쓴다. */
export function toChildCardAuthErrorMessage(error: unknown): string {
  if (!isAxiosError(error)) return DEFAULT_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;

  const { type } = (error.response.data ?? {}) as { type?: unknown };
  const typeName = typeof type === "string" ? type.split("/").pop() : undefined;
  return (typeName && CARD_AUTH_ERROR_MESSAGES[typeName]) || DEFAULT_MESSAGE;
}
