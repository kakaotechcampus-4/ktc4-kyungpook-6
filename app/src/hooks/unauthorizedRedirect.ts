import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { OWNER_LOGIN_PATH, setUnauthorizedHandler } from "../services/api";

/**
 * API 가 401 을 주면 화면만 로그인으로 바꾼다(앱을 다시 띄우지 않는다).
 *
 * services/api.ts 의 인터셉터는 라우터 밖이라 navigate 를 못 쓴다. 그래서 라우터 안쪽에서
 * navigate 를 쓰는 핸들러를 한 번 넣어 준다. 화면이 사라지면 기본값(주소 직접 바꾸기)으로 되돌린다.
 */
export const useUnauthorizedRedirect = () => {
  const navigate = useNavigate();

  useEffect(
    () => setUnauthorizedHandler(() => navigate(OWNER_LOGIN_PATH, { replace: true })),
    [navigate],
  );
};
