import { Preferences } from "@capacitor/preferences";

/*
  로그인 토큰을 폰에 저장한다. 앱을 껐다 켜도 남아 있어 다시 로그인하지 않아도 된다.

  Preferences 는 Android/iOS 의 네이티브 저장소에 쓴다(웹에서는 localStorage).
  localStorage 는 iOS 가 저장 공간이 모자랄 때 지울 수 있어서 쓰지 않는다.
  암호화는 하지 않는다.
*/
const ACCESS_TOKEN_KEY = "auth.accessToken";
const EXPIRES_AT_KEY = "auth.expiresAt";

export type AuthToken = {
  accessToken: string;
  /** 만료 시각(UTC, ISO 문자열). 로그인 응답의 expiresAt 그대로. */
  expiresAt: string;
};

export const saveAuthToken = async ({ accessToken, expiresAt }: AuthToken) => {
  await Promise.all([
    Preferences.set({ key: ACCESS_TOKEN_KEY, value: accessToken }),
    Preferences.set({ key: EXPIRES_AT_KEY, value: expiresAt }),
  ]);
};

export const clearAuthToken = async () => {
  await Promise.all([
    Preferences.remove({ key: ACCESS_TOKEN_KEY }),
    Preferences.remove({ key: EXPIRES_AT_KEY }),
  ]);
};

/**
 * 저장된 토큰을 꺼낸다. 없거나 만료됐으면 null.
 *
 * 만료된 토큰은 보내 봐야 401 이라 여기서 지우고 null 을 준다.
 */
export const getAuthToken = async (): Promise<string | null> => {
  const [{ value: accessToken }, { value: expiresAt }] = await Promise.all([
    Preferences.get({ key: ACCESS_TOKEN_KEY }),
    Preferences.get({ key: EXPIRES_AT_KEY }),
  ]);
  if (!accessToken) return null;

  if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
    await clearAuthToken();
    return null;
  }
  return accessToken;
};
