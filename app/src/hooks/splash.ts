import { useEffect } from "react";
import { useNavigate } from "react-router-dom";

/** 스플래시를 보여 주는 시간(ms). */
const SPLASH_DURATION_MS = 2000;

/**
 * 스플래시를 잠깐 보여 준 뒤 nextPath 로 넘긴다.
 *
 * replace 로 넘겨서 뒤로가기를 눌러도 스플래시로 돌아오지 않게 한다.
 */
export const useSplashRedirect = (nextPath: string) => {
  const navigate = useNavigate();

  useEffect(() => {
    const timer = setTimeout(() => {
      navigate(nextPath, { replace: true });
    }, SPLASH_DURATION_MS);

    return () => clearTimeout(timer);
  }, [navigate, nextPath]);
};
