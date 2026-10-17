import axios, { AxiosRequestConfig, AxiosResponse } from "axios";
import { clearAccessToken, getAccessToken } from "./authToken";
import { isUnauthorized } from "../utils/apiError";

/** 로그인 화면 경로. 토큰이 없거나 만료되면 여기로 보낸다. */
export const LOGIN_PATH = "/login";

/*
  개발 중에는 빈 값을 써서 /api/... 로 그냥 보낸다. 그러면 vite 개발 서버가 받아
  vite.config.ts 의 프록시로 백엔드에 넘겨준다 — 브라우저 입장에서는 같은 주소라 CORS 가 없다.
  배포 빌드에는 프록시가 없으므로 VITE_BACKEND_URL 에 백엔드 주소를 넣어야 한다.
*/
const baseURL = import.meta.env.DEV ? "" : import.meta.env.VITE_BACKEND_URL ?? "";

const axiosApiInstance = axios.create({
  baseURL,
  headers: {
    "Content-Type": "application/json",
  },
});

/*
  로그인하면 세션 스토리지에 accessToken 이 남는다. 있으면 모든 요청에 Bearer 로 싣는다.
  요청마다 다시 읽으므로 로그인·로그아웃 직후의 요청에도 바로 반영된다.
  호출부가 Authorization 을 직접 넣었으면 그 값을 덮어쓰지 않는다.
*/
axiosApiInstance.interceptors.request.use((config) => {
  const accessToken = getAccessToken();
  if (accessToken && !config.headers.has("Authorization")) {
    config.headers.set("Authorization", `Bearer ${accessToken}`);
  }
  return config;
});

/**
 * 로그인이 풀린 응답(401)을 한곳에서 처리한다.
 *
 * 이게 없으면 화면마다 다르게 어긋난다 — 가게 목록은 조회에 실패하면 **목업 데이터**를 대신
 * 보여주도록 돼 있어서, 로그인이 풀린 담당자가 진짜처럼 보이는 가짜 가게 목록을 읽게 된다
 * (PROMPT-150 에서 실제로 그렇게 나왔다). 권한 검사 스위치가 켜지면(PROMPT-146) 로그인 안 한
 * 사람은 전부 이 길로 들어온다.
 *
 * 로그인 요청 자체의 401(비밀번호 틀림)은 건드리지 않는다 — 그건 로그인 화면이 문구로 알려 줄 일이고,
 * 여기서 가로채면 입력하던 화면이 통째로 다시 뜬다.
 */
const LOGIN_URL = "/api/auth/login";

const handleUnauthorized = (error: unknown) => {
  if (!isUnauthorized(error)) return;

  const requestUrl = axios.isAxiosError(error) ? error.config?.url ?? "" : "";
  if (requestUrl.endsWith(LOGIN_URL)) return;

  clearAccessToken();

  /* 테스트는 node 환경이라 window 가 없다. 리다이렉트는 브라우저에서만 한다. */
  if (typeof window === "undefined") return;
  if (window.location.pathname === LOGIN_PATH) return;
  window.location.replace(LOGIN_PATH);
};

axiosApiInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    handleUnauthorized(error);
    return Promise.reject(error);
  }
);

const successHandler = <T>(response: AxiosResponse<T>) => {
  return response.data;
};

export const getRequest = async <T>(
  url: string,
  params?: Record<string, unknown>
) => {
  return axiosApiInstance.get<T>(url, { params }).then(successHandler);
};

export const postRequest = async <T>(
  url: string,
  payload: unknown,
  options?: AxiosRequestConfig
) => {
  return axiosApiInstance.post<T>(url, payload, options).then(successHandler);
};

export const putRequest = async <T>(
  url: string,
  payload: unknown,
  options?: AxiosRequestConfig
) => {
  return axiosApiInstance.put<T>(url, payload, options).then(successHandler);
};

export const patchRequest = async <T>(
  url: string,
  payload: unknown,
  options?: AxiosRequestConfig
) => {
  return axiosApiInstance.patch<T>(url, payload, options).then(successHandler);
};

export const deleteRequest = async <T>(
  url: string,
  params?: Record<string, unknown>
) => {
  return axiosApiInstance.delete<T>(url, { params }).then(successHandler);
};

/**
 * Orval 이 생성한 API 함수가 부르는 요청 함수(orval.config.ts 의 mutator).
 * 위 인스턴스를 그대로 쓰므로 baseURL·토큰 헤더가 손으로 쓴 요청과 똑같이 붙는다.
 */
export const customInstance = <T>(
  config: AxiosRequestConfig,
  options?: AxiosRequestConfig
): Promise<T> => {
  return axiosApiInstance<T>({ ...config, ...options }).then(successHandler);
};
