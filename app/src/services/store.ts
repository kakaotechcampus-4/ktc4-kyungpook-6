import { getRequest, putRequest } from "./api";

/** 백엔드 BusinessDay. 9/29 프론트와 합의한 값이다. */
export type BusinessDay = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";

/**
 * 백엔드 BusinessHourRequest·BusinessHourResponse. 같은 요일이 여러 줄일 수 있고(브레이크타임), 줄이 없는 요일은 휴무다.
 * 시간은 HH:mm. 자정에 닫으면 close 가 24:00, open 보다 이르면 다음 날 닫는다는 뜻이다.
 */
export type BusinessHour = {
  day: BusinessDay;
  open: string;
  close: string;
};

/** 백엔드 MenuResponse. 저장할 때 이 menuId 를 다시 실어 보내면 같은 메뉴를 고친다. */
export type Menu = {
  menuId: number;
  name: string;
  onSale: boolean;
  /** 메뉴 사진 파일 ID(GET /api/files/{fileId} 로 연다). 사진이 없으면 null. 저장(MenuRequest)으로는 바꿀 수 없다. */
  photoFileId: string | null;
};

/** 동반 인원. NONE(아이 혼자만) · ONE(1명까지 함께) · TWO(2명까지 함께). */
export type CompanionPolicy = "NONE" | "ONE" | "TWO";

/** 점주 홈의 영업 상태 방식. SCHEDULE(영업시간대로) · MANUAL(직접 켜고 끄기). */
export type OpenMode = "SCHEDULE" | "MANUAL";

/** 백엔드 OpenSettingRequest·OpenSettingResponse. */
export type OpenSetting = {
  mode: OpenMode;
  /** 직접 켜고 끄기에서 켜 두었는지. false 면 아동 앱에 "쉬는중" 으로 보인다. 영업시간대로일 때도 값은 남는다. */
  manualOpen: boolean;
};

/** 백엔드 StoreDetailResponse. GET /api/stores/{storeId} 의 응답. */
export type StoreDetailResponse = {
  storeId: number;
  /** 상호명. 점주는 바꿀 수 없다. */
  name: string;
  /** 도로명 주소. 점주는 바꿀 수 없다. */
  addressRoad: string;
  /** 가게 전화번호. 없으면 비어 있다. */
  phone: string | null;
  businessHours: BusinessHour[];
  menus: Menu[];
  /** 영업 상태 방식. 홈의 "영업시간대로 · 직접 켜고 끄기". */
  openMode: OpenMode;
  /** 직접 켜고 끄기에서 켜 두었는지. */
  manualOpen: boolean;
  /** 아이와 함께 올 수 있는 인원. 점주가 정하기 전에는 NONE. */
  companion: CompanionPolicy;
  /** 포장 가능. */
  takeoutAvailable: boolean;
  /** 매장 식사 가능. */
  dineInAvailable: boolean;
  /** 사장님 한마디. 적지 않았으면 비어 있다. */
  ownerNote: string | null;
};

/** 백엔드 MenuRequest. menuId 가 없으면 새 메뉴다. */
export type MenuRequest = {
  menuId?: number;
  name: string;
  onSale: boolean;
};

/**
 * 백엔드 OperatingInfoRequest. 통째로 바꾼다 — 목록에서 빠진 메뉴는 삭제된다.
 * 영업시간 1~21줄, 메뉴 1~50개, 전화번호 필수. 포장·매장 식사는 함께 보내고 하나는 true 여야 한다.
 * 사장님 한마디는 100자 이하 한 줄이고, 빈 문자열이면 지운다.
 */
export type OperatingInfoRequest = {
  phone: string;
  businessHours: BusinessHour[];
  menus: MenuRequest[];
  companion: CompanionPolicy;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  ownerNote: string;
};

/** VITE_STORE_MOCK 으로 백엔드 없이 화면을 볼 때 쓰는 목업(hooks/storeManage.ts). 상호명은 내 정보의 가게 이름을 넣는다. */
export const mockStoreDetail = (storeId: number, name: string): StoreDetailResponse => ({
  storeId,
  name,
  addressRoad: "대구광역시 북구 대학로 80",
  phone: "010-1234-5678",
  businessHours: [
    { day: "MON", open: "11:00", close: "21:00" },
    { day: "TUE", open: "11:00", close: "21:00" },
    { day: "WED", open: "11:00", close: "21:00" },
    { day: "THU", open: "11:00", close: "21:00" },
    { day: "FRI", open: "11:00", close: "21:00" },
    { day: "SAT", open: "12:00", close: "20:00" },
  ],
  menus: [
    { menuId: 1, name: "후라이드 치킨", onSale: true, photoFileId: null },
    { menuId: 2, name: "치킨마요 덮밥", onSale: false, photoFileId: null },
  ],
  openMode: "SCHEDULE",
  manualOpen: true,
  companion: "ONE",
  takeoutAvailable: true,
  dineInAvailable: true,
  ownerNote: "브레이크타임 15:00 – 17:00, 하루 5명까지 제공해요",
});

/** 가게 상세. 관리자와 그 가게에 연결된 점주만 부를 수 있다(아니면 403). */
export const getStoreDetail = (storeId: number) => {
  return getRequest<StoreDetailResponse>(`/api/stores/${storeId}`);
};

/** 가게 관리 "저장". 전부 반영되거나 하나도 반영되지 않는다. 저장된 가게 상세를 돌려준다. */
export const saveOperatingInfo = (storeId: number, payload: OperatingInfoRequest) => {
  return putRequest<StoreDetailResponse>(`/api/stores/${storeId}/operating-info`, payload);
};

/**
 * 홈의 영업 상태 저장. 방식과 켜고 끈 값을 함께 보낸다 — 영업시간대로로 바꿀 때도 manualOpen 을 보내야
 * 다시 직접 켜고 끄기로 바꿨을 때 그 값으로 돌아간다. 관리자와 그 가게에 연결된 점주만 부를 수 있다(아니면 403).
 *
 * 영업중·영업전 같은 상태는 서버가 계산하지 않는다. 앱이 이 값과 영업시간으로 계산한다(utils/openStatus.ts).
 */
export const saveOpenSetting = (storeId: number, payload: OpenSetting) => {
  return putRequest<OpenSetting>(`/api/stores/${storeId}/open-setting`, payload);
};
