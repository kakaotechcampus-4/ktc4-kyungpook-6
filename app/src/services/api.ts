import axios, { AxiosRequestConfig, AxiosResponse, isAxiosError } from "axios";
import { clearAuthToken, getAuthToken } from "./authToken";

/*
  frontend/src/services/api.ts 와 같은 방식이다.
  개발 중(npm run dev)에는 빈 값을 써서 vite 프록시로 백엔드에 넘긴다.
  앱(Android/iOS)에는 프록시가 없으므로 빌드할 때 VITE_BACKEND_URL 에 백엔드 전체 주소를 넣어야 한다.
*/
const baseURL = import.meta.env.DEV ? "" : import.meta.env.VITE_BACKEND_URL ?? "";

const axiosApiInstance = axios.create({
  baseURL,
  headers: {
    "Content-Type": "application/json",
  },
});

declare module "axios" {
  interface AxiosRequestConfig {
    /**
     * true 면 이 요청의 401 로는 로그인 화면으로 보내지 않는다.
     * 로그인 API 처럼 401 이 "비밀번호가 틀림"이라는 뜻인 요청에 붙인다.
     */
    skipAuthRedirect?: boolean;
  }
}

/** 토큰이 없거나 만료됐을 때 보낼 화면. */
export const OWNER_LOGIN_PATH = "/owner/login";

/*
  401 을 받았을 때 할 일. 라우터 밖이라 navigate 를 직접 쓸 수 없어서, 라우터 안쪽에서
  setUnauthorizedHandler 로 넣어 준다(components/UnauthorizedRedirect.tsx).
  기본값은 주소를 직접 바꾸는 것 — 핸들러가 아직 안 붙었을 때도 로그인 화면으로 간다.
  다만 이 방식은 앱(웹뷰)을 통째로 다시 띄워 화면 상태가 날아가고 흰 화면이 한 번 보인다.
*/
const redirectByLocation = () => {
  window.location.replace(OWNER_LOGIN_PATH);
};
let onUnauthorized: () => void = redirectByLocation;

/** 401 을 받았을 때 할 일을 바꾼다. 돌려주는 함수를 부르면 기본값으로 되돌린다. */
export const setUnauthorizedHandler = (handler: () => void) => {
  onUnauthorized = handler;
  return () => {
    if (onUnauthorized === handler) onUnauthorized = redirectByLocation;
  };
};

// 저장된 토큰이 있으면 모든 요청에 붙인다. 요청이 직접 넣은 Authorization(가입 상태 확인용 토큰 등)은 덮어쓰지 않는다.
axiosApiInstance.interceptors.request.use(async (config) => {
  if (config.headers.Authorization) return config;
  const accessToken = await getAuthToken();
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

/*
  401 은 토큰이 없거나 만료·위조됐다는 뜻이다. 어느 화면에서 나든 대응이 같아서 여기 한 곳에서 처리한다.
  403 은 "로그인은 했는데 권한이 없다"라 가로채지 않는다 — 로그인 화면으로 보내면 무한 왕복이 된다.
*/
axiosApiInstance.interceptors.response.use(undefined, async (error) => {
  if (isAxiosError(error) && error.response?.status === 401 && !error.config?.skipAuthRedirect) {
    await clearAuthToken();
    onUnauthorized();
  }
  return Promise.reject(error);
});

const successHandler = <T>(response: AxiosResponse<T>) => {
  return response.data;
};

export const postRequest = async <T>(
  url: string,
  payload: unknown,
  options?: AxiosRequestConfig
) => {
  return axiosApiInstance.post<T>(url, payload, options).then(successHandler);
};

export const getRequest = async <T>(url: string, options?: AxiosRequestConfig) => {
  return axiosApiInstance.get<T>(url, options).then(successHandler);
};
