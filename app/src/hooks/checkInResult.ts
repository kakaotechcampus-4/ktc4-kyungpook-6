import { useLocation, useNavigate } from "react-router-dom";
import type { CheckInResponse } from "../services/checkIn";
import { formatHourMinute } from "../utils/format";

/** 결과 없이 들어왔을 때 돌려보낼 QR 스캔 화면. */
export const QR_SCAN_PATH = "/owner/qr";

/*
  "조리 시작하기"를 누른 뒤 넘어갈 화면.
  점주 주문 화면이 아직 없어서 홈으로 둔다. 주문 화면이 생기면 여기만 바꾼다.
*/
const AFTER_CHECK_IN_PATH = "/";

export type UseCheckInResultResult = {
  /** 확인 시각("12:31"). 결과 없이 들어왔으면 null — 화면은 QR 스캔으로 돌려보낸다. */
  checkedAt: string | null;
  /** "조리 시작하기". */
  startCooking: () => void;
};

/**
 * 체크인 결과 화면. QR 스캔 화면이 location.state 로 넘긴 CheckInResponse 를 읽는다.
 *
 * 체크인 API 는 주문 정보를 주지 않아서(백엔드 PR #50, 주문 기능 전) 확인 시각만 보여 준다.
 */
export const useCheckInResult = (): UseCheckInResultResult => {
  const navigate = useNavigate();
  const result = useLocation().state as CheckInResponse | null;

  return {
    checkedAt: result?.checkedInAt ? formatHourMinute(result.checkedInAt) : null,
    startCooking: () => navigate(AFTER_CHECK_IN_PATH, { replace: true }),
  };
};
