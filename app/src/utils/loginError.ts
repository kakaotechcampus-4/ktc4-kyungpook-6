import { isAxiosError } from "axios";

/*
  로그인 API(POST /api/auth/login)가 줄 수 있는 에러와 화면 문구.
  백엔드 AuthController·AuthService·LoginRequest 와 docs/에러_처리_가이드.md 의 type 목록에 맞췄다.
  서버 title 은 "이메일 또는 …" 처럼 화면 라벨(아이디)과 말투가 달라서 앱 문구로 바꿔 보여 준다.
*/
const LOGIN_ERROR_MESSAGES: Record<string, string> = {
  // 401. 이메일이 없을 때와 비밀번호가 틀릴 때를 서버가 일부러 구분하지 않는다. 72바이트 넘는 비밀번호도 여기다.
  "invalid-credentials": "아이디 또는 비밀번호가 맞지 않아요.",
  // 403. 비밀번호까지 맞았을 때만 온다.
  "owner-pending-approval": "관리자 승인을 기다리고 있어요. 승인되면 로그인할 수 있어요.",
  "owner-rejected": "가입이 승인되지 않은 계정이에요.",
};

/*
  400 invalid-request 는 LoginRequest 검증 실패다. errors[].field 로 어느 칸인지 나눈다.
  email: @NotBlank·@Email·@Size(max=254), password: @NotBlank
*/
const INVALID_FIELD_MESSAGES: Record<string, string> = {
  email: "아이디는 이메일 형식으로 입력해 주세요.",
  password: "비밀번호를 입력해 주세요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "로그인하지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 에러 본문(RFC 9457). 스펙상 모든 필드가 없을 수 있다. */
type ProblemBody = {
  type?: unknown;
  title?: unknown;
  errors?: unknown;
};

const toTypeName = (type: unknown) => (typeof type === "string" ? type.split("/").pop() : undefined);

const toInvalidFieldMessage = (errors: unknown) => {
  if (!Array.isArray(errors)) return undefined;
  for (const fieldError of errors) {
    const message = INVALID_FIELD_MESSAGES[(fieldError as { field?: string })?.field ?? ""];
    if (message) return message;
  }
  return undefined;
};

/**
 * 로그인 실패를 화면 문구로 바꾼다.
 *
 * 에러 처리 가이드대로 type 으로 나눈다. 모르는 type 이면 서버 title 을,
 * 서버에 닿지 못했거나 title 도 없으면 기본 문구를 쓴다.
 */
export function toLoginErrorMessage(error: unknown): string {
  if (!isAxiosError(error)) return DEFAULT_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;

  const { type, title, errors } = (error.response.data ?? {}) as ProblemBody;
  const typeName = toTypeName(type);

  if (typeName === "invalid-request") {
    return toInvalidFieldMessage(errors) ?? INVALID_FIELD_MESSAGES.email;
  }
  if (typeName && LOGIN_ERROR_MESSAGES[typeName]) return LOGIN_ERROR_MESSAGES[typeName];
  if (typeof title === "string" && title) return title;
  return DEFAULT_MESSAGE;
}
