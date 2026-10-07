import { defineConfig } from "orval";
import { loadEnv } from "vite";

/*
  백엔드 Swagger JSON 으로 API 함수와 타입을 만든다.  npm run api:generate

  주소는 BACKEND_SWAGGER_URL 로 받는다. 셸 환경변수가 있으면 그것을, 없으면 frontend/.env 를 읽는다.
  VITE_ 접두사가 없으므로 빌드 결과물(브라우저)에는 들어가지 않는다 — 생성할 때만 쓰는 값이다.
*/
const env = { ...loadEnv("development", ".", ""), ...process.env };
const swaggerUrl = env.BACKEND_SWAGGER_URL;

if (!swaggerUrl) {
  throw new Error(
    "BACKEND_SWAGGER_URL 이 없습니다. frontend/.env 에 넣거나 환경변수로 넘기세요. 예) http://localhost:8080/v3/api-docs"
  );
}

export default defineConfig({
  api: {
    input: { target: swaggerUrl },
    output: {
      /* 생성 결과는 커밋하지 않는다(.gitignore). 배포 때 scripts/deploy.sh 가 다시 만든다. */
      target: "src/api/generated/endpoints.ts",
      schemas: "src/api/generated/model",
      client: "axios-functions",
      clean: true,
      override: {
        /* 생성된 함수도 services/api.ts 의 axios 인스턴스를 거쳐야 baseURL·토큰 헤더가 붙는다. */
        mutator: {
          path: "src/services/api.ts",
          name: "customInstance",
        },
      },
    },
  },
});
