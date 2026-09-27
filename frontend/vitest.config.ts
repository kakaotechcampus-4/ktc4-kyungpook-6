import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/*
  단위 테스트(Vitest) 설정입니다. CI의 Unit Test 단계에서 돕니다.

  vite.config.ts 와 나눠 둔 이유는 도는 대상이 다르기 때문입니다.
  vite.config.ts 는 개발 서버·배포 빌드 설정이고, 여기는 테스트 실행기 설정입니다.

  E2E(playwright.config.ts)와도 나뉩니다. 여기서 도는 것은 브라우저 없이
  함수만 부르는 테스트이고, 화면을 눌러보는 테스트는 Playwright 몫입니다.
*/
export default defineConfig({
  /* 컴포넌트 테스트(JSX)를 붙일 때를 대비해 걸어 둡니다. */
  plugins: [react()],
  test: {
    /* test/ 안의 *.test.ts 만 본다. test/e2e/ 는 *.spec.ts 라 걸리지 않는다. */
    include: ["test/**/*.test.{ts,tsx}"],
  },
});
