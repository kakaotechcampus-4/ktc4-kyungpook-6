import { postRequest } from "./api";

/** 백엔드 LoginRequest. */
export type LoginRequest = {
  email: string;
  password: string;
};

/** 백엔드 LoginResponse. 이후 요청에는 `Authorization: Bearer <accessToken>` 을 붙인다. */
export type LoginResponse = {
  accessToken: string;
  tokenType: "Bearer";
  /** 만료 시각(UTC, ISO 문자열). 지나면 다시 로그인한다. */
  expiresAt: string;
  role: "ADMIN" | "OWNER";
};

export const login = (payload: LoginRequest) => {
  return postRequest<LoginResponse>("/api/auth/login", payload);
};

/** 백엔드 OwnerSignupRequest. bizNo·phone 은 하이픈이 있어도 서버가 숫자만 남겨 저장한다. */
export type OwnerSignupRequest = {
  email: string;
  password: string;
  bizNo: string;
  storeName: string;
  representativeName: string;
  phone: string;
};

/** 백엔드 MemberResponse. 가입 직후 status 는 항상 PENDING(승인 대기)이다. */
export type MemberResponse = {
  memberId: number;
  email: string;
  role: "ADMIN" | "OWNER";
  status: "PENDING" | "APPROVED" | "REJECTED";
};

/** 점주 가입 신청. 성공하면 201, 관리자가 승인해야 로그인할 수 있다. */
export const signupOwner = (payload: OwnerSignupRequest) => {
  return postRequest<MemberResponse>("/api/auth/owners/signup", payload);
};

/**
 * 이메일(아이디)을 쓸 수 있는지 서버에 묻는다. 이미 가입된 이메일이면 false.
 *
 * TODO(백엔드 중복 확인 API — 다음 주 예정): API 가 생기면 아래 임시 구현을 그 호출로 바꾼다.
 *   예) return getRequest<{ available: boolean }>("/api/auth/email-availability", { email }).then((r) => r.available);
 *   주소·응답 모양은 백엔드가 정한 대로 맞춘다(위 예시는 추측이다).
 *   그 전까지는 항상 "쓸 수 있음"으로 답해서 중복 문구가 뜨지 않는다.
 *   중복은 가입 요청(POST /api/auth/owners/signup)의 409 duplicate-email 로도 알 수 있다.
 */
export const checkEmailAvailable = async (email: string): Promise<boolean> => {
  void email;
  return true;
};
