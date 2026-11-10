/*
  이 빌드가 누구를 위한 앱인지.

  점주 앱과 아동 앱은 같은 코드에서 나온다. 빌드할 때 VITE_APP_ROLE 로 역할을 박고
  APK 두 개로 가른다(app/scripts/build-apk.sh). 역할이 박혀 있으면 앱을 열었을 때
  역할 선택을 건너뛰고 그 역할의 로그인으로 바로 간다 — 아동 앱을 받은 아이에게
  "가게 사장님이에요" 를 고르게 할 이유가 없다.

  값이 없으면(npm run dev 로 웹에서 볼 때) 지금까지처럼 역할 선택이 뜬다.
*/

/** 앱을 쓰는 사람. 점주(OWNER)는 백엔드 MemberRole 과 이름을 맞췄다. */
export type AppUserRole = "OWNER" | "CHILD";

/** 역할을 고른 뒤 넘어갈 화면. 점주는 로그인, 아동은 급식카드 인증. */
export const LOGIN_PATHS: Record<AppUserRole, string> = {
  OWNER: "/owner/login",
  CHILD: "/child/login",
};

/** 역할이 박히지 않은 빌드가 스플래시 뒤에 보여 주는 화면. */
export const ROLE_SELECT_PATH = "/role-select";

/** VITE_APP_ROLE 값을 역할로 읽는다. 아는 값이 아니면 null — 역할 선택을 쓴다. */
export const resolveAppRole = (raw: string | undefined): AppUserRole | null =>
  raw === "OWNER" || raw === "CHILD" ? raw : null;

/** 스플래시가 끝난 뒤 갈 화면. */
export const splashNextPath = (role: AppUserRole | null): string =>
  role ? LOGIN_PATHS[role] : ROLE_SELECT_PATH;

/** 이 빌드에 박힌 역할. 역할 선택을 쓰는 빌드면 null. */
export const APP_ROLE = resolveAppRole(import.meta.env.VITE_APP_ROLE);

/** 이 빌드의 스플래시 다음 화면. */
export const SPLASH_NEXT_PATH = splashNextPath(APP_ROLE);
