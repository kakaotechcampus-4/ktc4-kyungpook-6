import type { BusinessDay, BusinessHour } from "../services/store";

/** Date.getDay() 순서(일요일 0). */
const DAYS_BY_INDEX: BusinessDay[] = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];

/** "HH:mm" → 그날 0시부터 지난 분. "24:00" 은 1440. */
const toMinutes = (time: string) => {
  const [hours, minutes] = time.split(":").map(Number);
  return hours * 60 + minutes;
};

/**
 * 가게 관리에 적힌 영업시간으로 그 시각에 영업 중인지 본다. 기기 시각 기준이다.
 *
 * 규칙은 백엔드 영업시간과 같다(docs/도메인_용어집.md "영업시간").
 * - 같은 요일이 여러 줄일 수 있다(브레이크타임). 줄이 없는 요일은 휴무다
 * - 닫는 시간이 여는 시간보다 이르면 다음 날 닫는다(예: 21:00~02:00). 그래서 전날 줄도 본다
 * - 자정에 닫으면 24:00, 24시간 영업은 00:00~24:00
 * - 여는 시각은 영업 중, 닫는 시각은 영업 종료다
 */
export const isOpenAt = (businessHours: BusinessHour[], at: Date) => {
  const now = at.getHours() * 60 + at.getMinutes();
  const today = DAYS_BY_INDEX[at.getDay()];
  const yesterday = DAYS_BY_INDEX[(at.getDay() + 6) % 7];

  return businessHours.some(({ day, open, close }) => {
    const openAt = toMinutes(open);
    const closeAt = toMinutes(close);
    const overnight = closeAt <= openAt;

    if (day === today) return overnight ? now >= openAt : now >= openAt && now < closeAt;
    // 전날 밤에 열어 오늘 새벽에 닫는 줄.
    if (day === yesterday && overnight) return now < closeAt;
    return false;
  });
};

/** 영업시간대로 볼 때의 영업 상태. */
export type ScheduleStatus = "OPEN" | "BEFORE_OPEN" | "ENDED" | "CLOSED_TODAY";

/**
 * 영업시간으로 그 시각의 영업 상태를 본다. 기기 시각 기준이다.
 *
 * - 영업시간 안이면 영업중(자정 넘김·브레이크타임은 isOpenAt 이 본다)
 * - 오늘 요일에 등록된 시간이 하나도 없으면 휴무
 * - 오늘 아직 열 시간이 남았으면 영업 전(브레이크타임 중에도 다음 시간이 남았으니 영업 전이다), 다 끝났으면 영업 종료
 */
export const getScheduleStatus = (businessHours: BusinessHour[], at: Date): ScheduleStatus => {
  if (isOpenAt(businessHours, at)) return "OPEN";

  const today = DAYS_BY_INDEX[at.getDay()];
  const todayHours = businessHours.filter(({ day }) => day === today);
  if (todayHours.length === 0) return "CLOSED_TODAY";

  const now = at.getHours() * 60 + at.getMinutes();
  return todayHours.some(({ open }) => toMinutes(open) > now) ? "BEFORE_OPEN" : "ENDED";
};
