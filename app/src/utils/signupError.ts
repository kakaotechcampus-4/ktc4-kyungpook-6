import { isAxiosError } from "axios";

/*
  가입 신청 API(POST /api/auth/owners/signup)가 줄 수 있는 에러.
  백엔드 AuthService.signupOwner·OwnerSignupRequest 와 docs/에러_처리_가이드.md 에 맞췄다.
  앱이 미리 같은 규칙으로 검사하므로 409 말고는 거의 오지 않는다.
*/
export type SignupError =
  /** 409 — 이미 가입된 이메일. 1단계 아이디 칸에 보여 준다. */
  | { kind: "duplicate-email" }
  /** 그 밖의 실패. 기본 정보 화면 버튼 위에 보여 준다. */
  | { kind: "message"; message: string };

const MESSAGES: Record<string, string> = {
  "invalid-biz-no": "사업자등록번호 10자리를 확인해주세요.",
  "invalid-phone": "휴대폰 번호를 확인해주세요.",
  "password-too-long": "비밀번호가 너무 길어요. 이전 화면에서 다시 입력해주세요.",
  "invalid-request": "입력한 정보를 다시 확인해주세요.",
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_MESSAGE = "가입 요청을 보내지 못했어요. 잠시 후 다시 시도해 주세요.";

export function toSignupError(error: unknown): SignupError {
  if (!isAxiosError(error)) return { kind: "message", message: DEFAULT_MESSAGE };
  if (!error.response) return { kind: "message", message: NETWORK_ERROR_MESSAGE };

  const { type, title } = (error.response.data ?? {}) as { type?: unknown; title?: unknown };
  const typeName = typeof type === "string" ? type.split("/").pop() : undefined;

  if (typeName === "duplicate-email") return { kind: "duplicate-email" };
  if (typeName && MESSAGES[typeName]) return { kind: "message", message: MESSAGES[typeName] };
  if (typeof title === "string" && title) return { kind: "message", message: title };
  return { kind: "message", message: DEFAULT_MESSAGE };
}
