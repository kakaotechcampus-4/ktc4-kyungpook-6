import axios, { AxiosRequestConfig, AxiosResponse } from "axios";
import { getAccessToken } from "./authToken";

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
