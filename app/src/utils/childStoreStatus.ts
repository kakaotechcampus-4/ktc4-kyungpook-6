import type { BusinessDay, BusinessHour } from "../services/store";
import type { ChildStore } from "../services/childStore";
import { isOpenAt } from "./openStatus";

/** 아동 가게 목록·가게 정보에 보이는 영업 상태. */
export type ChildStoreOpenStatus = "OPEN" | "BREAK" | "CLOSED_TODAY" | "BEFORE_OPEN" | "ENDED";

export const CHILD_STORE_STATUS_LABEL: Record<ChildStoreOpenStatus, string> = {
  OPEN: "영업중",
  BREAK: "쉬는중",
  CLOSED_TODAY: "오늘 휴무",
  BEFORE_OPEN: "영업 전",
  ENDED: "영업종료",
};

/** Date.getDay() 순서(일요일 0). */
const DAYS_BY_INDEX: BusinessDay[] = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];

/** "HH:mm" → 그날 0시부터 지난 분. */
const toMinutes = (time: string) => {
  const [hours, minutes] = time.split(":").map(Number);
  return hours * 60 + minutes;
};

/**
 * 그 시각의 영업 상태. 기기 시각 기준이다.
 *
 * - 직접 켜고 끄기(MANUAL)면 켜져 있으면 영업중, 꺼져 있으면 쉬는중
 * - 영업시간대로(SCHEDULE)면 영업시간 안이면 영업중. 자정 넘김·브레이크타임은 utils/openStatus.ts 의 isOpenAt 이 본다
 * - 영업시간 밖이면: 오늘 요일에 등록된 시간이 없으면 오늘 휴무, 오늘 아직 열 시간이 남았으면 영업 전, 다 끝났으면 영업종료
 */
export const getChildStoreOpenStatus = (
  store: Pick<ChildStore, "openMode" | "manualOpen" | "businessHours">,
  at: Date,
): ChildStoreOpenStatus => {
  if (store.openMode === "MANUAL") return store.manualOpen ? "OPEN" : "BREAK";
  if (isOpenAt(store.businessHours, at)) return "OPEN";

  const today = DAYS_BY_INDEX[at.getDay()];
  const todayHours = store.businessHours.filter(({ day }) => day === today);
  if (todayHours.length === 0) return "CLOSED_TODAY";

  const now = at.getHours() * 60 + at.getMinutes();
  return todayHours.some(({ open }) => toMinutes(open) > now) ? "BEFORE_OPEN" : "ENDED";
};

/** 1km 미만은 m, 이상은 소수 한 자리 km("350m", "1.2km"). */
export const formatDistance = (meters: number) =>
  meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`;

/** 가게 정보의 영업시간 목록 순서(월요일부터). */
export const WEEK_DAYS: { day: BusinessDay; label: string; short: string }[] = [
  { day: "MON", label: "월요일", short: "월" },
  { day: "TUE", label: "화요일", short: "화" },
  { day: "WED", label: "수요일", short: "수" },
  { day: "THU", label: "목요일", short: "목" },
  { day: "FRI", label: "금요일", short: "금" },
  { day: "SAT", label: "토요일", short: "토" },
  { day: "SUN", label: "일요일", short: "일" },
];

/** 그 시각의 요일. */
export const dayOf = (at: Date): BusinessDay => DAYS_BY_INDEX[at.getDay()];

/**
 * 그 요일의 영업시간 한 줄("11:00 – 21:00"). 여러 줄이면 쉼표로 잇고(브레이크타임), 없으면 "휴무".
 * 자정을 넘기는 줄은 적힌 그대로 보인다("17:00 – 03:00").
 */
export const formatDayHours = (businessHours: BusinessHour[], day: BusinessDay) => {
  const ranges = businessHours
    .filter((hour) => hour.day === day)
    .sort((a, b) => toMinutes(a.open) - toMinutes(b.open))
    .map(({ open, close }) => `${open} – ${close}`);
  return ranges.length > 0 ? ranges.join(", ") : "휴무";
};
