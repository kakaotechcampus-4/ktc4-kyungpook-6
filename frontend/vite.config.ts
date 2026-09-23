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
        개발 서버(5173)로 들어온 /api 요청을 백엔드(8080)로 넘긴다.

        브라우저는 포트가 다르면 다른 사이트로 보고 요청을 막는다(CORS).
        프록시를 두면 브라우저 입장에서는 같은 5173으로 보내는 것이라 막히지 않고,
        실제 전달은 서버끼리 하므로 CORS가 끼어들 자리가 없다.

        이건 개발 서버에만 있는 기능이라 배포 빌드에는 영향이 없다.
        배포에서는 백엔드가 CORS 를 허용하거나 같은 도메인 뒤에 두어야 한다.
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
