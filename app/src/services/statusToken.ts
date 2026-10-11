import { Preferences } from "@capacitor/preferences";

/*
  가입 상태 확인용 토큰(statusToken, 백엔드 PR #87)을 폰에 저장한다.
  승인을 기다리는 동안 앱을 껐다 켜도 GET /api/auth/me 로 승인 여부를 확인할 수 있게 한다.

  로그인 토큰(authToken.ts)과 키를 나눈다. 이 토큰으로는 /api/auth/me 만 부를 수 있어서,
  섞이면 다른 API 에 이 토큰이 붙어 403 이 난다.
*/
const STATUS_TOKEN_KEY = "signup.statusToken";
const EXPIRES_AT_KEY = "signup.statusTokenExpiresAt";

export type StatusToken = {
  statusToken: string;
  /** 만료 시각(UTC, ISO 문자열). 가입 응답의 statusTokenExpiresAt 그대로. */
  statusTokenExpiresAt: string;
};

export const saveStatusToken = async ({ statusToken, statusTokenExpiresAt }: StatusToken) => {
  await Promise.all([
    Preferences.set({ key: STATUS_TOKEN_KEY, value: statusToken }),
    Preferences.set({ key: EXPIRES_AT_KEY, value: statusTokenExpiresAt }),
  ]);
};

export const clearStatusToken = async () => {
  await Promise.all([
    Preferences.remove({ key: STATUS_TOKEN_KEY }),
    Preferences.remove({ key: EXPIRES_AT_KEY }),
  ]);
};

/** 저장된 토큰을 꺼낸다. 없거나 만료됐으면 지우고 null. */
export const getStatusToken = async (): Promise<string | null> => {
  const [{ value: statusToken }, { value: expiresAt }] = await Promise.all([
    Preferences.get({ key: STATUS_TOKEN_KEY }),
    Preferences.get({ key: EXPIRES_AT_KEY }),
  ]);
  if (!statusToken) return null;

  if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
    await clearStatusToken();
    return null;
  }
  return statusToken;
};
