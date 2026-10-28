import { isAxiosError } from "axios";

/*
  가입 신청 API(POST /api/auth/owners/signup)와 그 앞에 부르는 파일 업로드(POST /api/files)가 줄 수 있는 에러.
  백엔드 AuthService.signupOwner·OwnerSignupRequest·UploadFileService 와 docs/에러_처리_가이드.md 에 맞췄다.
  입력값은 앱이 미리 같은 규칙으로 검사하므로 409 와 파일 에러 말고는 거의 오지 않는다.
*/
export type SignupError =
  /** 409 — 이미 가입된 이메일. 1단계 아이디 칸에 보여 준다. */
  | { kind: "duplicate-email" }
  /** 그 밖의 실패. 마지막 단계(메뉴) 화면 버튼 위에 보여 준다. */
  | { kind: "message"; message: string };

const MESSAGES: Record<string, string> = {
  "invalid-biz-no": "사업자등록번호 10자리를 확인해주세요.",
  "invalid-phone": "휴대폰 번호를 확인해주세요.",
  "password-too-long": "비밀번호가 너무 길어요. 이전 화면에서 다시 입력해주세요.",
  "invalid-request": "입력한 정보를 다시 확인해주세요.",
  "empty-file": "빈 파일은 올릴 수 없어요. 사진이나 사업자 등록증을 다시 골라주세요.",
  "unsupported-file-type": "사진은 JPG·PNG, 사업자 등록증은 PDF·JPG·PNG 파일만 올릴 수 있어요.",
  "file-too-large": "파일이 너무 커요. 사진은 5MB, PDF 사업자 등록증은 10MB 까지 올릴 수 있어요.",
  "invalid-file-reference": "올린 파일을 쓸 수 없어요. 잠시 후 다시 시도해 주세요.",
  "too-many-requests": "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.",
  "signup-temporarily-closed": "지금은 가입 신청을 받을 수 없어요. 잠시 후 다시 시도해 주세요.",
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
