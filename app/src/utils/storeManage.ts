import { isAxiosError } from "axios";
import type { BusinessDay, BusinessHour, OperatingInfoRequest } from "../services/store";
import { isNotImplemented } from "../services/store";

/** 화면에 보이는 순서. */
export const BUSINESS_DAYS: BusinessDay[] = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"];

export const DAY_LABELS: Record<BusinessDay, string> = {
  MON: "월요일",
  TUE: "화요일",
  WED: "수요일",
  THU: "목요일",
  FRI: "금요일",
  SAT: "토요일",
  SUN: "일요일",
};

/* 백엔드 OperatingInfoRequest·BusinessHourRequest·MenuRequest 의 상한과 같다. */
export const MAX_BUSINESS_HOUR_LINES = 21;
export const MAX_MENU_COUNT = 50;
export const MAX_MENU_NAME_LENGTH = 50;
export const MAX_PHONE_LENGTH = 20;

// 백엔드 BusinessHourRequest 의 정규식 그대로. 닫는 시간은 00:00 대신 24:00 으로 적는다.
const OPEN_TIME = /^([01]\d|2[0-3]):[0-5]\d$/;
const CLOSE_TIME = /^(?!00:00)(([01]\d|2[0-3]):[0-5]\d|24:00)$/;

/** 숫자만 받아 입력하는 대로 HH:mm 으로 만든다. 최대 4자리. */
export const formatTimeInput = (value: string) => {
  const digits = value.replace(/\D/g, "").slice(0, 4);
  if (digits.length <= 2) return digits;
  return `${digits.slice(0, 2)}:${digits.slice(2)}`;
};

/** "시간 추가"로 새로 넣을 줄. 마지막 줄의 다음 요일에 같은 시간으로 채운다. 줄이 없으면 월요일 09:00~18:00. */
export const nextBusinessHour = (hours: BusinessHour[]): BusinessHour => {
  const last = hours[hours.length - 1];
  if (!last) return { day: "MON", open: "09:00", close: "18:00" };
  const nextDay = BUSINESS_DAYS[(BUSINESS_DAYS.indexOf(last.day) + 1) % BUSINESS_DAYS.length];
  return { day: nextDay, open: last.open, close: last.close };
};

/**
 * 저장 전에 백엔드와 같은 규칙으로 검사한다. 틀린 곳이 있으면 첫 번째 문구, 없으면 null.
 * 같은 요일 시간이 겹치는지는 백엔드 서비스가 본다(400).
 */
export const validateOperatingInfo = ({ phone, businessHours, menus }: OperatingInfoRequest) => {
  if (phone.trim() === "") return "가게 전화번호를 입력해 주세요.";
  if (phone.length > MAX_PHONE_LENGTH) return `가게 전화번호는 ${MAX_PHONE_LENGTH}자를 넘을 수 없어요.`;

  if (businessHours.length === 0) return "영업시간을 한 줄 이상 넣어 주세요.";
  if (businessHours.length > MAX_BUSINESS_HOUR_LINES) {
    return `영업시간은 ${MAX_BUSINESS_HOUR_LINES}줄까지 넣을 수 있어요.`;
  }
  for (const { day, open, close } of businessHours) {
    const label = DAY_LABELS[day];
    if (!OPEN_TIME.test(open)) return `${label} 여는 시간을 00:00 ~ 23:59 로 입력해 주세요.`;
    if (!CLOSE_TIME.test(close)) return `${label} 닫는 시간을 00:01 ~ 24:00 으로 입력해 주세요.`;
    if (open === close) return `${label} 여는 시간과 닫는 시간이 같아요. 24시간 영업은 00:00 ~ 24:00 이에요.`;
  }

  if (menus.length === 0) return "메뉴를 한 개 이상 등록해 주세요.";
  if (menus.length > MAX_MENU_COUNT) return `메뉴는 ${MAX_MENU_COUNT}개까지 등록할 수 있어요.`;
  return null;
};

const NETWORK_ERROR_MESSAGE = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
const DEFAULT_SAVE_MESSAGE = "저장하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 저장(PUT /api/stores/{storeId}/operating-info) 실패 문구. docs/에러_처리_가이드.md 대로
 * errors 가 있으면 첫 칸의 문구를, 없으면 title 을 보여 준다.
 */
export const toSaveErrorMessage = (error: unknown) => {
  if (isNotImplemented(error)) return "저장 기능은 아직 준비 중이에요. 바꾼 내용은 저장되지 않았어요.";
  if (!isAxiosError(error)) return DEFAULT_SAVE_MESSAGE;
  if (!error.response) return NETWORK_ERROR_MESSAGE;

  const { title, errors } = (error.response.data ?? {}) as { title?: unknown; errors?: unknown };
  if (Array.isArray(errors)) {
    const first = errors.find(
      (item): item is { message: string } => typeof item?.message === "string" && item.message !== "",
    );
    if (first) return first.message;
  }
  if (typeof title === "string" && title) return title;
  return DEFAULT_SAVE_MESSAGE;
};
