import type { CapacitorConfig } from "@capacitor/cli";

/*
  Capacitor 설정입니다.

  webDir 은 vite build 결과물 폴더입니다. `npx cap sync` 가 이 폴더를
  android/ · ios/ 네이티브 프로젝트 안으로 복사합니다.
  그래서 웹 코드를 고친 뒤에는 항상 build → sync 순서로 돌려야 앱에 반영됩니다.
*/
const config: CapacitorConfig = {
  /*
    appId·appName 은 cap 이 네이티브 프로젝트를 처음 만들 때 쓰는 값이다. 지금은 안드로이드
    쪽 진짜 값이 플레이버에 있다(android/app/build.gradle) — 폰에 깔리는 것은 그쪽이다.
  */
  appId: "com.ktc4kyungpook6.goodradar",
  appName: "Good radar",
  webDir: "dist",
  android: {
    /*
      플레이버를 둘로 가른 뒤로 cap 이 어느 쪽을 돌릴지 정해 줘야 한다. 없으면 cap 이
      assembleDebug 를 돌리고 apk/debug/app-debug.apk 를 찾는데, 플레이버가 생긴 뒤로
      그 경로는 없다(apk/child/·apk/owner/ 뿐).

      npm run android 는 폰에 꽂아 보며 고칠 때 쓰는 길이라 점주로 둔다. 아동 쪽을 보려면
      `npx cap run android --flavor child` 로 덮어쓴다. APK 두 개를 뽑는 것은
      scripts/build-apk.sh 가 따로 한다.
    */
    flavor: "owner",
  },
};

export default config;
