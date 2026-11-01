import defaultStorePhoto from "../assets/child-store-thumb.jpg";
import type { BusinessHour } from "./store";

/**
 * 점주가 가게 정보에서 고르는 동반 인원. 아이 본인만(NONE)이면 배지를 띄우지 않는다.
 * TODO(점주 앱에 동반 인원 선택이 생기면): 그쪽 값과 이름을 맞춘다.
 */
export type CompanionPolicy = "NONE" | "ONE" | "TWO";

/** 아동 가게 목록의 가게 한 곳. */
export type ChildStore = {
  storeId: number;
  name: string;
  /** 목록에 보이는 짧은 주소("북구 대학로 80"). */
  addressShort: string;
  /** 아동 위치에서 가게까지 거리(m). */
  distanceMeters: number;
  /** 가게 사진. 점주가 사진을 정하지 않은 가게는 기본 사진(마스코트)이 온다. */
  photoUrl: string;
  takeoutAvailable: boolean;
  companion: CompanionPolicy;
  /** 점주 홈의 영업 상태 방식. MANUAL 이면 manualOpen 대로, SCHEDULE 이면 영업시간대로. */
  openMode: "SCHEDULE" | "MANUAL";
  manualOpen: boolean;
  businessHours: BusinessHour[];
  /** 검색에 쓰는 메뉴 이름. */
  menus: string[];
};

const EVERY_DAY = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
const WEEKDAYS_AND_SAT = ["MON", "TUE", "WED", "THU", "FRI", "SAT"] as const;

const hoursOf = (days: readonly BusinessHour["day"][], ...ranges: [string, string][]): BusinessHour[] =>
  days.flatMap((day) => ranges.map(([open, close]) => ({ day, open, close })));

/*
  백엔드에 아동용 가게 목록 API 가 아직 없어서 피그마 시안의 가게로 화면을 채운다.
  영업 상태가 골고루 보이도록 영업시간을 다르게 두었다(24시간, 자정 넘김, 브레이크타임, 주말만, 직접 끔).
  TODO(가게 목록 API 가 생기면): 이 목업을 지우고 API 를 부른다. 거리도 서버가 계산해 준다.
*/
export const MOCK_CHILD_STORES: ChildStore[] = [
  {
    storeId: 1,
    name: "맛나 치킨",
    addressShort: "북구 대학로 80",
    distanceMeters: 350,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "ONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    menus: ["후라이드 치킨", "양념 치킨"],
  },
  {
    storeId: 2,
    name: "행복 닭강정",
    addressShort: "북구 대학로 112",
    distanceMeters: 450,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    companion: "TWO",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    menus: ["닭강정", "치킨마요 덮밥"],
  },
  {
    storeId: 3,
    name: "행복분식",
    addressShort: "북구 산격동 45",
    distanceMeters: 520,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "NONE",
    openMode: "MANUAL",
    manualOpen: true,
    businessHours: hoursOf(WEEKDAYS_AND_SAT, ["10:00", "20:00"]),
    menus: ["떡볶이", "김밥", "라면"],
  },
  {
    storeId: 4,
    name: "바삭 치킨",
    addressShort: "북구 대현동 33",
    distanceMeters: 610,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 자정을 넘겨 다음 날 새벽에 닫는다.
    businessHours: hoursOf(EVERY_DAY, ["17:00", "03:00"]),
    menus: ["후라이드 치킨", "치킨 너겟"],
  },
  {
    storeId: 5,
    name: "엄마손 도시락",
    addressShort: "북구 복현동 21",
    distanceMeters: 780,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "ONE",
    // 점주가 직접 꺼 둔 가게. 영업시간 안이어도 쉬는중이다.
    openMode: "MANUAL",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    menus: ["제육 도시락", "돈가스 도시락"],
  },
  {
    storeId: 6,
    name: "동네 치킨집",
    addressShort: "북구 복현동 58",
    distanceMeters: 940,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    companion: "TWO",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 브레이크타임이 있다.
    businessHours: hoursOf(WEEKDAYS_AND_SAT, ["11:00", "14:00"], ["17:00", "22:00"]),
    menus: ["반반 치킨", "치킨 덮밥"],
  },
  {
    storeId: 7,
    name: "버거 스테이션",
    addressShort: "북구 대현동 16",
    distanceMeters: 1200,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["10:00", "22:00"]),
    menus: ["치즈버거", "감자튀김"],
  },
  {
    storeId: 8,
    name: "치킨플러스",
    addressShort: "북구 산격동 71",
    distanceMeters: 1300,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 주말에만 연다. 평일에는 오늘 휴무.
    businessHours: hoursOf(["SAT", "SUN"], ["11:00", "21:00"]),
    menus: ["순살 치킨"],
  },
  {
    storeId: 9,
    name: "김치찌개 하우스",
    addressShort: "북구 산격동 12",
    distanceMeters: 1500,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: false,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    // 아침에만 연다. 이른 아침에는 영업 전, 그 뒤로는 영업종료.
    businessHours: hoursOf(EVERY_DAY, ["06:00", "09:00"]),
    menus: ["김치찌개", "된장찌개"],
  },
  {
    storeId: 10,
    name: "멀리 국밥",
    addressShort: "북구 태전동 9",
    // 적정 거리 밖이라 목록에 나오지 않는다.
    distanceMeters: 2400,
    photoUrl: defaultStorePhoto,
    takeoutAvailable: true,
    companion: "NONE",
    openMode: "SCHEDULE",
    manualOpen: false,
    businessHours: hoursOf(EVERY_DAY, ["00:00", "24:00"]),
    menus: ["돼지국밥"],
  },
];
