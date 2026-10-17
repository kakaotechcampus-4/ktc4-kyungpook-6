import type { BusinessDay } from "../services/auth";

/** 요일 고르기 칸의 순서와 화면 이름. 값은 백엔드 BusinessDay 그대로. */
export const BUSINESS_DAYS: { value: BusinessDay; label: string }[] = [
  { value: "MON", label: "월요일" },
  { value: "TUE", label: "화요일" },
  { value: "WED", label: "수요일" },
  { value: "THU", label: "목요일" },
  { value: "FRI", label: "금요일" },
  { value: "SAT", label: "토요일" },
  { value: "SUN", label: "일요일" },
];

/** 시간 고르기 칸의 간격(분). */
const TIME_STEP_MINUTES = 30;

const toTime = (minutes: number) =>
  `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;

const timesBetween = (fromMinutes: number, toMinutes: number) => {
  const times: string[] = [];
  for (let minutes = fromMinutes; minutes <= toMinutes; minutes += TIME_STEP_MINUTES) {
    times.push(toTime(minutes));
  }
  return times;
};

/*
  시간은 <input type="time"> 이 아니라 30분 간격 목록에서 고른다.
  - 시간 입력 칸은 24:00 을 적을 수 없는데, 백엔드는 자정에 닫는 것을 24:00 으로 받는다(00:00 은 하루의 시작).
  - 시간 입력 칸은 기기 언어에 따라 "오후 9:00" 처럼 보여 피그마의 21:00 과 다르다.
*/
/** 여는 시간: 00:00 ~ 23:30 (백엔드 OPEN_TIME 00:00 ~ 23:59). */
export const OPEN_TIME_OPTIONS = timesBetween(0, 24 * 60 - TIME_STEP_MINUTES);
/** 닫는 시간: 00:30 ~ 24:00 (백엔드 CLOSE_TIME 00:01 ~ 24:00). 여는 시간보다 이르면 다음 날 닫는다는 뜻이다. */
export const CLOSE_TIME_OPTIONS = timesBetween(TIME_STEP_MINUTES, 24 * 60);
