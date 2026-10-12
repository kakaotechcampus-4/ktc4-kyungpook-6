import { postRequest } from "./api";

/** 백엔드 CheckInResponse. 아동을 알아볼 수 있는 값은 일부러 없다. */
export type CheckInResponse = {
  checkInId: number;
  /**
   * 체크인 시각. 백엔드 LocalDateTime 이라 시간대 없이 온다(예: "2026-09-29T12:00:00").
   * Date 로 바꾸면 기기 시간대로 다시 해석되므로 글자 그대로 쓴다.
   */
  checkedInAt: string;
};

/**
 * QR 체크인(백엔드 PR #50). 스캔한 문자열을 **손대지 않고** 그대로 보낸다.
 * 앞뒤 공백·개행이 붙으면 서버가 invalid-qr-token 으로 거절한다.
 *
 * 점주는 자기에게 연결된 가게(GET /api/auth/me 의 stores)로만 부를 수 있다. 아니면 403 forbidden.
 */
export const checkIn = (storeId: number, qrPayload: string) => {
  return postRequest<CheckInResponse>(`/api/stores/${storeId}/check-ins`, { qrPayload });
};
