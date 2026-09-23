import axios, { AxiosRequestConfig, AxiosResponse } from "axios";

const baseURL = import.meta.env.VITE_BACKEND_URL ?? "localhost:3000";

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
