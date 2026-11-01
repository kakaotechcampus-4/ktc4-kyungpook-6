import type { BusinessDay } from "../services/store";
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
