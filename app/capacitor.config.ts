import type { CapacitorConfig } from "@capacitor/cli";

/*
  Capacitor 설정입니다.

  webDir 은 vite build 결과물 폴더입니다. `npx cap sync` 가 이 폴더를
  android/ · ios/ 네이티브 프로젝트 안으로 복사합니다.
  그래서 웹 코드를 고친 뒤에는 항상 build → sync 순서로 돌려야 앱에 반영됩니다.
*/
const config: CapacitorConfig = {
  appId: "com.ktc4.app",
  appName: "KTC4 App",
  webDir: "dist",
};

export default config;
