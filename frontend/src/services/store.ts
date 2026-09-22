import { getRequest, patchRequest, postRequest } from "./api";

/**
 * 백엔드 StoreStatus enum.
 * (OPEN: 영업중, SUSPENDED: 휴업, CLOSED: 폐업, UNKNOWN: 미확인)
 */
export type StoreStatus = "OPEN" | "SUSPENDED" | "CLOSED" | "UNKNOWN";

export type StoreResponse = {
  storeId: number;
  name: string;
  addressRoad: string | null;
  lat: number | null;
  lng: number | null;
  status: StoreStatus;
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

/**
 * 가게 정보 수정 요청. 백엔드 StoreUpdateRequest와 필드가 1:1로 대응한다.
 *
 * 부분 수정이라 네 필드 모두 선택이다. 담아 보낸 필드만 바뀌고
 * 빠뜨린 필드는 서버가 기존 값을 그대로 둔다. 그래서 화면에서 건드리지 않은 칸은
 * 아예 담지 않아야 한다 — 빈 문자열로 담으면 지우라는 뜻이 된다.
 *
 * 길이·공백 제한은 서버가 검증한다(가게명 1~200자, 도로명 주소 1~500자,
 * 전화번호 20자 이하, 가게명·주소는 공백만 불가). 전화번호만 빈 문자열로 지울 수 있다.
 */
export type StoreUpdateRequest = {
  name?: string;
  addressRoad?: string;
  phone?: string;
  status?: StoreStatus;
};

/**
 * 가게 정보를 수정한다. 담아 보낸 필드만 바뀐다.
 *
 * 확인일 갱신은 이 API가 아니라 confirmStore다.
 * 아직 스펙만 공개된 단계라 서버는 501을 돌려준다(구현 PR 이후 200/400/404).
 *
 * @example
 * await updateStore(1, { phone: "053-123-4567" });
 */
export const updateStore = async (
  storeId: number,
  request: StoreUpdateRequest
) => {
  return patchRequest<StoreResponse>(`/api/stores/${storeId}`, request);
};

/**
 * 담당자가 가게 정보를 직접 확인했음을 기록한다. 요청 본문은 없고
 * 확인 시각(lastCheckedAt)은 서버가 현재 시각으로 찍는다.
 *
 * 수정 API와 나뉘어 있는 이유는 같은 요청을 여러 번 보내도 결과가 같아야 하는
 * PATCH와 달리 확인 기록은 호출할 때마다 시각이 바뀌기 때문이다.
 * 아직 스펙만 공개된 단계라 서버는 501을 돌려준다(구현 PR 이후 200/404).
 *
 * @example
 * await confirmStore(1);
 */
export const confirmStore = async (storeId: number) => {
  return postRequest<StoreResponse>(`/api/stores/${storeId}/confirm`, undefined);
};
