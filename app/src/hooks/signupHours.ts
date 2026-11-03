import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { newDraftItemId, useSignupDraft } from "./signupDraft";
import type { SignupBusinessHour } from "./signupDraft";
import { BUSINESS_HOUR_MAX_LINES, validateBusinessHour } from "../utils/signupValidation";

/** 영업시간 다음 화면(메뉴). */
const NEXT_PATH = "/owner/signup/store/menus";

type HourField = "day" | "open" | "close";

const emptyLine = (): SignupBusinessHour => ({ id: newDraftItemId(), day: "", open: "", close: "" });

export type UseSignupHoursResult = {
  lines: SignupBusinessHour[];
  /** 한 줄의 요일·여는 시간·닫는 시간 중 하나를 바꾼다. */
  changeLine: (id: string, field: HourField, value: string) => void;
  /** "시간 추가". 21줄(백엔드 상한)이면 false 라 버튼을 끈다. */
  canAddLine: boolean;
  addLine: () => void;
  removeLine: (id: string) => void;
  /** 한 줄 이상이고 모든 줄이 맞을 때만 true. 겹치는 줄은 백엔드도 가입 때는 보지 않아 여기서도 보지 않는다. */
  canProceed: boolean;
  goNext: () => void;
};

/** 가게 등록 2: 영업시간. 같은 요일을 여러 줄 적을 수 있고(브레이크타임), 줄이 없는 요일은 휴무다. */
export const useSignupHours = (): UseSignupHoursResult => {
  const navigate = useNavigate();
  const { businessHours, setBusinessHours } = useSignupDraft();

  // 처음 들어오면 빈 줄 하나로 시작한다.
  const [lines, setLines] = useState<SignupBusinessHour[]>(() => businessHours ?? [emptyLine()]);

  const changeLine = (id: string, field: HourField, value: string) => {
    setLines((prev) => prev.map((line) => (line.id === id ? { ...line, [field]: value } : line)));
  };

  const canAddLine = lines.length < BUSINESS_HOUR_MAX_LINES;

  const canProceed =
    lines.length > 0 && lines.every((line) => validateBusinessHour(line) === null);

  const goNext = () => {
    if (!canProceed) return;
    setBusinessHours(lines);
    navigate(NEXT_PATH);
  };

  return {
    lines,
    changeLine,
    canAddLine,
    addLine: () => {
      if (canAddLine) setLines((prev) => [...prev, emptyLine()]);
    },
    removeLine: (id) => setLines((prev) => prev.filter((line) => line.id !== id)),
    canProceed,
    goNext,
  };
};
