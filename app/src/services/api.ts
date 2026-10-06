import axios, { AxiosRequestConfig, AxiosResponse } from "axios";

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
