import { getRequest } from "./api";

export type StoreResponse = {
  storeId: number;
  name: string;
  addressRoad: string | null;
  lat: number | null;
  lng: number | null;
  // Store.Status의 enum 값이 확정되면 문자열 리터럴 유니온으로 좁힌다.
  status: string;
  category: string | null;
  phone: string | null;
  bizNo: string | null;
  lastCheckedAt: string | null;
};

export type PageResponse<T> = {
  content: T[];
  page: number;
  limit: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export type GetStoresParams = {
  /** 0부터 시작하는 페이지 번호. 기본값은 0이며 음수는 서버에서 0으로 보정한다. */
  page?: number;
  /** 페이지당 건수. 기본값은 20이며 서버에서 1~100 범위로 보정한다. */
  limit?: number;
};

/**
 * 가게 목록과 페이지 정보를 조회한다.
 * @example
 * const { content, hasNext } = await getStores({ page: 0, limit: 20 });
 */
export const getStores = async (params: GetStoresParams = {}) => {
  const response = await getRequest<PageResponse<StoreResponse>>("/api/stores", params);
  return response;
};
