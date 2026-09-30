import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/** 로컬에서 백엔드를 띄우는 기본 주소. .env 의 VITE_BACKEND_URL 로 덮어쓴다. */
const DEFAULT_BACKEND_URL = "http://localhost:8080";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");

  return {
    plugins: [react(), tailwindcss()],
    server: {
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
