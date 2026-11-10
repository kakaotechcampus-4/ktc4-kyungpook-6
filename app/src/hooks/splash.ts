import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { APP_ROLE } from "../appRole";
import type { AppUserRole } from "../appRole";
import { clearAuthToken, getAuthRole } from "../services/authToken";
import type { AuthRole } from "../services/authToken";
import { clearStatusToken, getStatusToken } from "../services/statusToken";

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
 *   단, 이 빌드의 역할과 다른 사람의 로그인이면 버리고 nextPath 로 간다(decideSplashPath).
 * - 둘 다 없으면 nextPath(역할 선택).
 *
 * 만료된 토큰은 getStatusToken·getAuthRole 이 null 을 줘서 다음 갈래로 넘어간다.
 * replace 로 넘겨서 뒤로가기를 눌러도 스플래시로 돌아오지 않게 한다.
 */
export type SplashDecision = {
  path: string;
  /** 이 빌드의 역할과 맞지 않아 버릴 저장 로그인. 없으면 null. */
  discard: "auth" | "status" | null;
};

/**
 * 저장된 로그인으로 어디로 갈지 정한다. 순수 함수라 따로 시험한다.
 *
 * 역할이 박힌 빌드(appRole)는 **다른 역할의 로그인을 쓰지 않는다.** 웹판은 아동·점주가 한 도메인
 * (`/app/child`·`/app/owner`)이라 저장소(localStorage)를 같이 쓴다 — 점주로 로그인한 브라우저로
 * `/app/child` 를 열면 아동 앱이 점주 홈으로 튀었고, 반대도 마찬가지였다(운영 2026-11-11).
 * 맞지 않는 로그인은 버린다 — 두면 아동 화면이 점주 토큰으로 API 를 불러 403 만 본다.
 * 가입 상태 토큰은 점주 가입의 것이라 아동 빌드에서는 같은 이유로 버린다.
 */
export const decideSplashPath = (
  appRole: AppUserRole | null,
  statusToken: string | null,
  authRole: AuthRole | null,
  nextPath: string,
): SplashDecision => {
  if (statusToken && appRole === "CHILD") return { path: nextPath, discard: "status" };
  if (statusToken) return { path: SIGNUP_COMPLETE_PATH, discard: null };
  if (authRole && appRole && authRole !== appRole) return { path: nextPath, discard: "auth" };
  if (authRole) return { path: HOME_PATHS[authRole], discard: null };
  return { path: nextPath, discard: null };
};

export const useSplashRedirect = (nextPath: string) => {
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;

    const timer = setTimeout(async () => {
      const [statusToken, authRole] = await Promise.all([getStatusToken(), getAuthRole()]);
      if (cancelled) return;

      const { path, discard } = decideSplashPath(APP_ROLE, statusToken, authRole, nextPath);
      if (discard === "auth") await clearAuthToken();
      if (discard === "status") await clearStatusToken();
      if (cancelled) return;

      navigate(path, { replace: true });
    }, SPLASH_DURATION_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [navigate, nextPath]);
};
