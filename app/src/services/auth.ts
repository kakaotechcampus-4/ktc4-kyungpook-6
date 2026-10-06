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
