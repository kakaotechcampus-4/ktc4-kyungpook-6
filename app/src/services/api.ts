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

/** 로그인 API. 이 요청의 401 은 "비밀번호가 틀림"이라 로그인 화면으로 보내지 않는다. */
const LOGIN_URL = "/api/auth/login";
/** 토큰이 없거나 만료됐을 때 보낼 화면. */
const LOGIN_PAGE_PATH = "/owner/login";

// 저장된 토큰이 있으면 모든 요청에 붙인다.
axiosApiInstance.interceptors.request.use(async (config) => {
  const accessToken = await getAuthToken();
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

/*
  401 은 토큰이 없거나 만료·위조됐다는 뜻이다. 토큰을 지우고 로그인 화면으로 보낸다.
  라우터 밖이라 navigate 를 쓸 수 없어서 주소를 직접 바꾼다(앱을 새로 여는 것과 같다).
*/
axiosApiInstance.interceptors.response.use(undefined, async (error) => {
  if (
    isAxiosError(error) &&
    error.response?.status === 401 &&
    error.config?.url !== LOGIN_URL
  ) {
    await clearAuthToken();
    window.location.replace(LOGIN_PAGE_PATH);
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
