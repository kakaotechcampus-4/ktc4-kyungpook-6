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
