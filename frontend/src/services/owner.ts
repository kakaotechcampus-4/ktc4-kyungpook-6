import { deleteRequest, getRequest, postRequest } from './api';
import type { PageResponse, StoreStatus } from './store';

/** 회원 상태. 가입 신청 목록을 이 값으로 나눠 본다. */
export type MemberStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** 신청서 상호명과 가게명이 얼마나 같은가. */
export type NameMatch = 'EXACT' | 'PARTIAL' | 'NONE';

/** 국세청 기준 사업자 상태. 배치가 마지막으로 확인한 값이라 비어 있을 수 있다. */
export type BusinessState = 'ACTIVE' | 'SUSPENDED' | 'CLOSED' | 'NOT_REGISTERED';

/** 관리자가 보는 점주 가입 신청 한 건. */
export type OwnerApplicationResponse = {
  memberId: number;
  email: string;
  /** 사업자등록번호(숫자 10자리). */
  bizNo: string | null;
  /** 신청서에 적은 상호명. 가게 데이터의 가게명과 자동으로 이어지지 않는다. */
  storeName: string | null;
  representativeName: string | null;
  /** 점주 본인 휴대폰 번호(숫자만). 이 칸이 생기기 전 신청은 비어 있다. */
  phone: string | null;
  /** 가입 때 적은 가게 주소. 저장이 붙기 전 신청은 비어 있다. */
  storeAddress: string | null;
  storePhone: string | null;
  status: MemberStatus;
  appliedAt: string | null;
  /** 승인하거나 거절한 시각. 처리 전에는 비어 있다. */
  reviewedAt: string | null;
};

/**
 * 점주에게 연결할 수 있는 후보 가게 한 곳.
 *
 * **서버는 후보를 찾아 줄 뿐 확정하지 않습니다.** 신청서의 번호·상호명은 본인이 적은 값이라
 * 그것만으로 가게를 정하면 남의 가게에 연결될 수 있습니다. 그래서 화면은 어디까지 일치하는지
 * (`bizNoMatched`·`phoneMatched`·`nameMatch`)와 이미 연결된 점주 수를 함께 보여 줍니다.
 */
export type StoreCandidateResponse = {
  storeId: number;
  name: string;
  addressRoad: string | null;
  bizNo: string | null;
  /** 매장 번호. 점주 개인 번호가 아니다. */
  phone: string | null;
  status: StoreStatus;
  bizNoMatched: boolean;
  /** 신청서 휴대폰 번호와 가게에 등록된 점주 휴대폰 번호가 같은가. 번호 자체는 내려오지 않는다. */
  phoneMatched: boolean;
  nameMatch: NameMatch;
  /** 이 가게에 이미 연결된 점주 수. 1 이상이면 재가입·양수·사칭 여부를 확인한다. */
  linkedOwnerCount: number;
  ntsState: BusinessState | null;
};

/** 가입 신청 목록. 상태를 주지 않으면 승인 대기다. */
export const getOwnerApplications = async (
  status: MemberStatus = 'PENDING',
  page = 0,
  limit = 20,
) =>
  getRequest<PageResponse<OwnerApplicationResponse>>('/api/admin/owners', {
    status,
    page,
    limit,
  });

/** 이 신청에 연결할 수 있는 후보 가게. 후보가 없으면 빈 배열이다. */
export const getStoreCandidates = async (memberId: number) =>
  getRequest<StoreCandidateResponse[]>(
    `/api/admin/owners/${memberId}/store-candidates`,
  );

/**
 * 가입을 승인하면서 가게를 함께 확정한다.
 *
 * **가게 없이는 승인할 수 없습니다** — 승인만 되고 가게가 없는 점주는 어느 가게에도
 * 체크인할 수 없는 계정이 됩니다. 이미 처리한 신청은 409 입니다.
 */
export const approveOwner = async (memberId: number, storeId: number) =>
  postRequest<OwnerApplicationResponse>(
    `/api/admin/owners/${memberId}/approve`,
    { storeId },
  );

/** 가입을 거절한다. 이미 처리한 신청은 409 다. */
export const rejectOwner = async (memberId: number) =>
  postRequest<OwnerApplicationResponse>(
    `/api/admin/owners/${memberId}/reject`,
    undefined,
  );

/** 승인된 점주에게 가게를 하나 더 연결한다. */
export const linkStore = async (memberId: number, storeId: number) =>
  postRequest<void>(`/api/admin/owners/${memberId}/stores/${storeId}`, undefined);

/** 점주와 가게의 연결을 끊는다. */
export const unlinkStore = async (memberId: number, storeId: number) =>
  deleteRequest<void>(`/api/admin/owners/${memberId}/stores/${storeId}`);
