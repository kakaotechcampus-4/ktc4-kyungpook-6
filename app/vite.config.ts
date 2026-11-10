import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/** 로컬에서 백엔드를 띄우는 기본 주소. .env 의 VITE_BACKEND_URL 로 덮어쓴다. */
const DEFAULT_BACKEND_URL = "http://localhost:8080";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");

  return {
    /*
      웹으로 올릴 때 하위 경로를 받는다(예: /app/child/). 값이 없으면 "/" 라서
      APK 빌드는 지금까지와 같다 — Capacitor 는 루트에서 연다.
      라우터도 같은 값을 쓴다(App.tsx 의 basename = import.meta.env.BASE_URL).
    */
    base: env.VITE_BASE_PATH || "/",

    plugins: [react(), tailwindcss()],
    server: {
      /*
        포트를 고정한다. 백엔드 CORS 에 이 주소가 들어가 있어야 브라우저로 앱 화면을 볼 수
        있는데, vite 는 기본 포트가 막히면 조용히 다음 번호로 옮겨 간다 — 그러면 CORS 에서
        403 이 나고 화면에는 "인증하지 못했어요" 로만 보여 원인이 안 드러난다.
        관리자 웹(frontend)이 5173 을 쓰므로 비켜 둔다. 역할별로 둘을 같이 띄울 때는
        점주 쪽을 `--port 5176` 으로 덮어쓴다.
      */
      port: 5175,
      strictPort: true,

      /*
        브라우저로 개발할 때(npm run dev) /api 요청을 백엔드(8080)로 넘긴다.
        자세한 이유는 frontend/vite.config.ts 주석과 같다.

        네이티브 앱(Android/iOS) 안에서는 개발 서버를 거치지 않으므로 이 프록시가 없다.
        앱에서는 백엔드 전체 주소로 요청해야 한다.
      */
      proxy: {
        "/api": {
          target: env.VITE_BACKEND_URL || DEFAULT_BACKEND_URL,
          changeOrigin: true,
        },
      },
    },
  };
});
