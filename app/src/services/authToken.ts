import { Preferences } from "@capacitor/preferences";

/*
  로그인 토큰을 폰에 저장한다. 앱을 껐다 켜도 남아 있어 다시 로그인하지 않아도 된다.

  Preferences 는 Android/iOS 의 네이티브 저장소에 쓴다(웹에서는 localStorage).
  localStorage 는 iOS 가 저장 공간이 모자랄 때 지울 수 있어서 쓰지 않는다.
  암호화는 하지 않는다.
*/
const ACCESS_TOKEN_KEY = "auth.accessToken";
const EXPIRES_AT_KEY = "auth.expiresAt";
const ROLE_KEY = "auth.role";

/**
 * 누구의 토큰인지. 점주 로그인과 아동 카드 인증이 같은 자리에 토큰을 두므로(한 폰에 한 사람),
 * 스플래시가 어느 홈으로 보낼지, 401 이면 어느 로그인으로 보낼지 이 값으로 정한다.
 */
export type AuthRole = "OWNER" | "CHILD";

export type AuthToken = {
  accessToken: string;
  /** 만료 시각(UTC, ISO 문자열). 로그인 응답의 expiresAt 그대로. */
  expiresAt: string;
};

export const saveAuthToken = async ({ accessToken, expiresAt }: AuthToken, role: AuthRole) => {
  await Promise.all([
    Preferences.set({ key: ACCESS_TOKEN_KEY, value: accessToken }),
    Preferences.set({ key: EXPIRES_AT_KEY, value: expiresAt }),
    Preferences.set({ key: ROLE_KEY, value: role }),
  ]);
};

export const clearAuthToken = async () => {
  await Promise.all([
    Preferences.remove({ key: ACCESS_TOKEN_KEY }),
    Preferences.remove({ key: EXPIRES_AT_KEY }),
    Preferences.remove({ key: ROLE_KEY }),
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
    // 역할은 남겨 둔다. 만료 뒤 401 이 나면 그 주인의 로그인 화면으로 보내야 한다(getLastAuthRole).
    await Promise.all([Preferences.remove({ key: ACCESS_TOKEN_KEY }), Preferences.remove({ key: EXPIRES_AT_KEY })]);
    return null;
  }
  return accessToken;
};

/**
 * 저장된 토큰의 주인. 토큰이 없거나 만료됐으면 null.
 * 역할을 저장하기 전에 로그인한 토큰은 점주 로그인밖에 없었으니 OWNER 로 본다.
 */
export const getAuthRole = async (): Promise<AuthRole | null> => {
  if (!(await getAuthToken())) return null;
  const { value } = await Preferences.get({ key: ROLE_KEY });
  return value === "CHILD" ? "CHILD" : "OWNER";
};

/**
 * 마지막으로 로그인한 사람. 토큰이 만료돼도 남아 있어 401 일 때 어느 로그인 화면으로 보낼지 정한다.
 * 한 번도 로그인하지 않았으면 OWNER(점주 로그인으로 보내던 예전 동작).
 */
export const getLastAuthRole = async (): Promise<AuthRole> => {
  const { value } = await Preferences.get({ key: ROLE_KEY });
  return value === "CHILD" ? "CHILD" : "OWNER";
};
