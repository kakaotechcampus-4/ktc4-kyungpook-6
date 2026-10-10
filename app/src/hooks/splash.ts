import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { getStatusToken } from "../services/statusToken";

/** 스플래시를 보여 주는 시간(ms). */
const SPLASH_DURATION_MS = 2000;

/** 승인을 기다리는 중이면 보낼 가입 완료(승인 대기) 화면. */
const SIGNUP_COMPLETE_PATH = "/owner/signup/complete";

/**
 * 스플래시를 잠깐 보여 준 뒤 nextPath 로 넘긴다.
 *
 * 가입 상태 확인용 토큰이 남아 있으면(가입하고 승인을 기다리는 중) nextPath 대신 가입 완료 화면으로 보낸다.
 * 그 화면이 토큰으로 승인 여부를 확인한다. 토큰이 만료됐으면 getStatusToken 이 null 을 줘서 nextPath 로 간다.
 *
 * replace 로 넘겨서 뒤로가기를 눌러도 스플래시로 돌아오지 않게 한다.
 */
export const useSplashRedirect = (nextPath: string) => {
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;

    const timer = setTimeout(async () => {
      const statusToken = await getStatusToken();
      if (cancelled) return;
      navigate(statusToken ? SIGNUP_COMPLETE_PATH : nextPath, { replace: true });
    }, SPLASH_DURATION_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [navigate, nextPath]);
};
