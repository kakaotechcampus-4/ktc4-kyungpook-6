import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getAuthRole } from "../services/authToken";
import { getStatusToken } from "../services/statusToken";

/** 스플래시를 보여 주는 시간(ms). */
const SPLASH_DURATION_MS = 2000;

/** 승인을 기다리는 중이면 보낼 가입 완료(승인 대기) 화면. */
const SIGNUP_COMPLETE_PATH = "/owner/signup/complete";

/** 로그인해 둔 사람을 보낼 홈. */
const HOME_PATHS = { OWNER: "/home", CHILD: "/child/home" } as const;

/**
 * 스플래시를 잠깐 보여 준 뒤 다음 화면으로 넘긴다.
 *
 * - 가입 상태 확인용 토큰이 남아 있으면(가입하고 승인을 기다리는 중) 가입 완료 화면. 그 화면이 토큰으로 승인 여부를 확인한다.
 * - 로그인 토큰이 남아 있으면 그 주인의 홈(점주 홈·아동 홈). 앱을 열 때마다 역할 선택·로그인을 다시 거치지 않게 한다.
 * - 둘 다 없으면 nextPath(역할 선택).
 *
 * 만료된 토큰은 getStatusToken·getAuthRole 이 null 을 줘서 다음 갈래로 넘어간다.
 * replace 로 넘겨서 뒤로가기를 눌러도 스플래시로 돌아오지 않게 한다.
 */
export const useSplashRedirect = (nextPath: string) => {
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;

    const timer = setTimeout(async () => {
      const [statusToken, authRole] = await Promise.all([getStatusToken(), getAuthRole()]);
      if (cancelled) return;

      const path = statusToken ? SIGNUP_COMPLETE_PATH : authRole ? HOME_PATHS[authRole] : nextPath;
      navigate(path, { replace: true });
    }, SPLASH_DURATION_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [navigate, nextPath]);
};
