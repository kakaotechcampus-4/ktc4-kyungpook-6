import defaultStorePhoto from "../assets/child-store-thumb.jpg";
import { getRequest } from "./api";
import type { BusinessHour, CompanionPolicy, OpenMode } from "./store";

export type { CompanionPolicy };

/** 위치를 받지 못했을 때 지도의 기준점(경북대 근처). 목업 가게 거리도 이 점에서 잰 값이다. */
export const DEFAULT_CHILD_LOCATION = { lat: 35.8906, lng: 128.612 };

/** 아동 가게 목록의 가게 한 곳. */
export type ChildStore = {
  storeId: number;
  name: string;
  /** 목록에 보이는 짧은 주소("북구 대학로 80"). */
  addressShort: string;
  /** 아동 위치에서 가게까지 거리(m). */
  distanceMeters: number;
  /** 지도에 핀을 꽂을 위치(위도·경도). */
  lat: number;
  lng: number;
  /** 가게 사진. 점주가 사진을 정하지 않은 가게는 기본 사진(마스코트)이 온다. */
  photoUrl: string;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  companion: CompanionPolicy;
  /** 점주 홈의 영업 상태 방식. MANUAL 이면 manualOpen 대로, SCHEDULE 이면 영업시간대로. */
  openMode: "SCHEDULE" | "MANUAL";
  manualOpen: boolean;
  businessHours: BusinessHour[];
  /** 가게 전화번호. */
  phone: string;
  /** 사장님 한마디. 점주가 적지 않았으면 null — 가게 정보에 칸을 그리지 않는다. */
  ownerNote: string | null;
  menus: ChildStoreMenu[];
};

/** 가게 메뉴. 판매하지 않는 메뉴는 "오늘은 안 돼요"로 흐리게 보인다. */
export type ChildStoreMenu = {
  menuId: number;
  name: string;
  onSale: boolean;
};

/**
 * 주변 가게 API 에 보내는 반경(m). 서버 상한(100km)과 같다 — 대구 전역이라 사실상 가게 전부를 받는다.
 * 2km 였는데, 가맹점이 흩어져 있어 내 위치 근처가 비어 보여서 풀었다(2026-11-11). 목록은 여전히 가까운 순이다.
 */
export const NEARBY_RADIUS_METERS = 100_000;

/** 백엔드 NearbyStoreResponse. 가게·메뉴 사진은 담지 않는다. */
type NearbyStoreResponse = {
  storeId: number;
  name: string;
  addressRoad: string;
  lat: number;
  lng: number;
  distanceMeters: number;
  phone: string | null;
  openMode: OpenMode;
  manualOpen: boolean;
  businessHours: BusinessHour[];
  menus: ChildStoreMenu[];
  companion: CompanionPolicy;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  ownerNote: string | null;
};

/** 목록에 보이는 짧은 주소. 맨 앞의 시·도("대구광역시")를 뗀다. 시·도로 시작하지 않으면 그대로. */
export const toShortAddress = (addressRoad: string) => {
  const [first, ...rest] = addressRoad.trim().split(/\s+/);
  return rest.length > 0 && /(시|도)$/.test(first) ? rest.join(" ") : addressRoad.trim();
};

const toChildStore = (store: NearbyStoreResponse): ChildStore => ({
  storeId: store.storeId,
  name: store.name,
  addressShort: toShortAddress(store.addressRoad),
  distanceMeters: store.distanceMeters,
  lat: store.lat,
  lng: store.lng,
  // 사진은 API 에 없어 모든 가게가 기본 사진이다.
  photoUrl: defaultStorePhoto,
  takeoutAvailable: store.takeoutAvailable,
  dineInAvailable: store.dineInAvailable,
  companion: store.companion,
  openMode: store.openMode,
  manualOpen: store.manualOpen,
  businessHours: store.businessHours,
  phone: store.phone ?? "",
  ownerNote: store.ownerNote || null,
  menus: store.menus,
});

/**
 * 주변 가게(GET /api/stores/nearby). 반경 안의 가게를 가까운 순으로, 거리와 함께 준다. 아동 토큰이 필요하다.
 * 가게 상세 API 는 따로 없어 가게 정보·주문 화면도 이 목록에서 찾는다(hooks/childNearbyStores.ts).
 * 영업 상태는 서버가 계산하지 않는다. 앱이 openMode·manualOpen·영업시간으로 계산한다(utils/childStoreStatus.ts).
 */
export const getNearbyStores = async ({ lat, lng }: { lat: number; lng: number }) => {
  const stores = await getRequest<NearbyStoreResponse[]>("/api/stores/nearby", {
    params: { lat, lng, radius: NEARBY_RADIUS_METERS },
  });
  return stores.map(toChildStore);
};

/** 메뉴 이름 목록을 메뉴로 만든다. 이름 앞에 "-" 를 붙이면 오늘 판매하지 않는 메뉴. */
const menusOf = (storeId: number, ...names: string[]): ChildStoreMenu[] =>
  names.map((name, index) => ({
    menuId: storeId * 100 + index + 1,
    name: name.replace(/^-/, ""),
    onSale: !name.startsWith("-"),
  }));

const EVERY_DAY = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
const WEEKDAYS_AND_SAT = ["MON", "TUE", "WED", "THU", "FRI", "SAT"] as const;

const hoursOf = (days: readonly BusinessHour["day"][], ...ranges: [string, string][]): BusinessHour[] =>
  days.flatMap((day) => ranges.map(([open, close]) => ({ day, open, close })));

/*
  VITE_STORE_MOCK=true 로 백엔드 없이 화면을 볼 때 쓰는 가게. 피그마 시안의 가게다.
  영업 상태가 골고루 보이도록 영업시간을 다르게 두었다(24시간, 자정 넘김, 브레이크타임, 주말만, 직접 끔).
  위치(lat·lng)는 경북대 북문(DEFAULT_CHILD_LOCATION) 둘레에 거리와 대략 맞게 두었다.
*/
export const MOCK_CHILD_STORES: ChildStore[] = [
  {
    storeId: 1,
    name: "맛나 치킨",
    addressShort: "북구 대학로 80",
    distanceMeters: 350,
    lat: 35.8926,
    lng: 128.6145,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: true,
    companion: "ONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 피그마 시안의 영업시간. 일요일은 휴무.
    businessHours: [
      ...hoursOf(["MON", "TUE", "WED", "THU", "FRI"], ["11:00", "15:00"], ["17:00", "21:00"]),
      ...hoursOf(["SAT"], ["12:00", "20:00"]),
    ],
    phone: "053-107-4561",
    ownerNote: "브레이크타임 15:00 – 17:00, 하루 5명까지 제공해요",
    menus: menusOf(1, "후라이드 치킨", "-치킨마요 덮밥"),
  },
  {
    storeId: 2,
    name: "행복 닭강정",
    addressShort: "북구 대학로 112",
    distanceMeters: 450,
    lat: 35.8871,
    lng: 128.6145,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    dineInAvailable: true,
    companion: "TWO",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    phone: "053-114-4562",
    ownerNote: null,
    menus: menusOf(2, "닭강정", "치킨마요 덮밥"),
  },
  {
    storeId: 3,
    name: "행복분식",
    addressShort: "북구 산격동 45",
    distanceMeters: 520,
    lat: 35.8936,
    lng: 128.608,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: false,
    companion: "NONE",
    openMode: "MANUAL",
    manualOpen: true,
    businessHours: hoursOf(WEEKDAYS_AND_SAT, ["10:00", "20:00"]),
    phone: "053-121-4563",
    ownerNote: null,
    menus: menusOf(3, "떡볶이", "김밥", "라면"),
  },
  {
    storeId: 4,
    name: "바삭 치킨",
    addressShort: "북구 대현동 33",
    distanceMeters: 610,
    lat: 35.8866,
    lng: 128.6075,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 자정을 넘겨 다음 날 새벽에 닫는다.
    businessHours: hoursOf(EVERY_DAY, ["17:00", "03:00"]),
    phone: "053-128-4564",
    ownerNote: null,
    menus: menusOf(4, "후라이드 치킨", "치킨 너겟"),
  },
  {
    storeId: 5,
    name: "엄마손 도시락",
    addressShort: "북구 복현동 21",
    distanceMeters: 780,
    lat: 35.8856,
    lng: 128.618,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: true,
    companion: "ONE",
    // 점주가 직접 꺼 둔 가게. 영업시간 안이어도 쉬는중이다.
    openMode: "MANUAL",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    phone: "053-135-4565",
    ownerNote: "재료 준비 중이라 잠시 쉬어요",
    menus: menusOf(5, "제육 도시락", "돈가스 도시락"),
  },
  {
    storeId: 6,
    name: "동네 치킨집",
    addressShort: "북구 복현동 58",
    distanceMeters: 940,
    lat: 35.8976,
    lng: 128.6175,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    dineInAvailable: true,
    companion: "TWO",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 브레이크타임이 있다.
    businessHours: hoursOf(WEEKDAYS_AND_SAT, ["11:00", "14:00"], ["17:00", "22:00"]),
    phone: "053-142-4566",
    ownerNote: null,
    menus: menusOf(6, "반반 치킨", "치킨 덮밥"),
  },
  {
    storeId: 7,
    name: "버거 스테이션",
    addressShort: "북구 대현동 16",
    distanceMeters: 1200,
    lat: 35.8826,
    lng: 128.603,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: false,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["10:00", "22:00"]),
    phone: "053-149-4567",
    ownerNote: null,
    menus: menusOf(7, "치즈버거", "감자튀김"),
  },
  {
    storeId: 8,
    name: "치킨플러스",
    addressShort: "북구 산격동 71",
    distanceMeters: 1300,
    lat: 35.8886,
    lng: 128.598,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 주말에만 연다. 평일에는 오늘 휴무.
    businessHours: hoursOf(["SAT", "SUN"], ["11:00", "21:00"]),
    phone: "053-156-4568",
    ownerNote: null,
    menus: menusOf(8, "순살 치킨"),
  },
  {
    storeId: 9,
    name: "김치찌개 하우스",
    addressShort: "북구 산격동 12",
    distanceMeters: 1500,
    lat: 35.9026,
    lng: 128.606,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    dineInAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 아침에만 연다. 이른 아침에는 영업 전, 그 뒤로는 영업종료.
    businessHours: hoursOf(EVERY_DAY, ["06:00", "09:00"]),
    phone: "053-163-4569",
    ownerNote: null,
    menus: menusOf(9, "김치찌개", "된장찌개"),
  },
  {
    storeId: 10,
    name: "멀리 국밥",
    addressShort: "북구 태전동 9",
    // 적정 거리 밖이라 목록에 나오지 않는다.
    distanceMeters: 2400,
    lat: 35.9106,
    lng: 128.622,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    dineInAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    phone: "053-170-4570",
    ownerNote: null,
    menus: menusOf(10, "돼지국밥"),
  },
];
