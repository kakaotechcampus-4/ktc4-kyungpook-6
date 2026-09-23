import { defineConfig, devices } from "@playwright/test";

const BASE_URL = "http://localhost:5173";

/*
  CI의 E2E Test 단계에서 도는 설정입니다.

  백엔드 없이 돕니다. 조회가 실패하면 화면이 목업으로 떨어지도록 만들어져 있어
  가게 카드가 그려지고, 서버로 나가는 요청은 각 테스트에서 page.route 로 가로채
  원하는 응답(501·200·400)을 돌려줍니다. 진짜 백엔드에 기대면 DB 상태에 따라
  결과가 흔들려서 "고친 것도 없는데 빨간불"이 되기 때문입니다.
*/
export default defineConfig({
  testDir: "./test/e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "html",
  use: {
    baseURL: BASE_URL,
    /* 실패했을 때만 남긴다 — 통과한 실행까지 기록하면 용량만 먹는다. */
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
  /* 테스트를 돌리면 개발 서버를 알아서 띄운다. 이미 떠 있으면 그걸 쓴다. */
  webServer: {
    command: "npm run dev",
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
