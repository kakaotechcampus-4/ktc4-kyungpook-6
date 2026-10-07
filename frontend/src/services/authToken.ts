/**
 * 로그인 토큰 보관. 세션 스토리지에 두므로 탭을 닫으면 지워진다.
 *
 * 키 이름을 여기 한 곳에서만 정한다. 인터셉터(api.ts)와 로그인 화면이 같은 키를 봐야 한다.
 */
export const ACCESS_TOKEN_KEY = "accessToken";

export const getAccessToken = () => sessionStorage.getItem(ACCESS_TOKEN_KEY);

export const saveAccessToken = (accessToken: string) =>
  sessionStorage.setItem(ACCESS_TOKEN_KEY, accessToken);

export const clearAccessToken = () => sessionStorage.removeItem(ACCESS_TOKEN_KEY);
