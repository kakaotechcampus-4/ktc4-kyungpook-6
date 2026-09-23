import axios, { AxiosRequestConfig, AxiosResponse } from "axios";

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
