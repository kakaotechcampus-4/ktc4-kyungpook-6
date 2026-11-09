import { postRequest } from "./api";
import type { AuthToken } from "./authToken";

/** 백엔드 ChildAuthResponse. 칸 이름이 점주 로그인 응답과 같아 같은 저장 코드(saveAuthToken)를 쓴다. */
export type ChildAuthResponse = AuthToken & { tokenType: string };

/**
 * 급식카드 번호로 인증하고 아동 토큰을 받는다(토큰은 7일). 처음 보는 카드면 서버가 아동을 새로 만든다.
 * 실제 급식카드인지는 확인하지 않고 숫자 16자리인지만 본다(시연용).
 * 이 요청의 401 은 없지만, 실패를 로그인 화면 이동으로 바꾸지 않게 skipAuthRedirect 를 붙인다.
 */
export const authenticateChildCard = (cardNumber: string) =>
  postRequest<ChildAuthResponse>("/api/auth/children/card", { cardNumber }, { skipAuthRedirect: true });
