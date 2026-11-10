# frontend — 관리자 웹

담당자가 가게 목록을 보고 조사를 시작하고, 조사 결과(수정안)를 검토·반영하는 관리자 화면.
점주 가입 신청 승인도 여기서 한다. 백엔드 API 만 부른다.

## 실행

준비물: Node 24 (CI·배포 이미지와 같은 버전)

```bash
cd frontend
npm ci
cp .env.example .env          # 백엔드 주소를 바꿀 때만 고친다
npm run api:generate          # 백엔드 Swagger 로 API 코드 생성 → src/api/generated/
npm run dev
```

`http://localhost:5173` 에서 열린다. 개발 서버가 `/api` 요청을 `VITE_BACKEND_URL`(기본 `http://localhost:8080`)로
넘기므로, 백엔드를 먼저 띄워 둔다. 띄우는 법은 [`../backend/README.md`](../backend/README.md).

> **`npm run api:generate` 는 백엔드가 떠 있어야 한다.** 생성 코드(`src/api/generated/`)는 커밋하지 않아서
> 새로 받은 코드에는 없고, 없으면 `npm run build` 와 타입체크가 import 를 못 찾아 실패한다.
> 백엔드 API 가 바뀌었을 때도 다시 돌린다.

| 화면 | 주소 |
| --- | --- |
| 로그인 | `/login` |
| 가게 목록 · 조사 시작 | `/` |
| 조사 결과 | `/analysis`, `/analysis/:jobId` |
| 점주 가입 승인 | `/owners` |

## 테스트

```bash
./scripts/test.sh     # API 코드 생성(없을 때) → eslint → 타입체크 → vitest. CI 도 이 스크립트를 쓴다
npm run test:e2e      # Playwright E2E. CI 에서는 돌지 않는다
```

- 단위 테스트는 `test/hooks`·`test/services`·`test/utils`, E2E 는 `test/e2e` 에 둔다.
- E2E 는 백엔드 없이 돈다. 서버 요청은 각 테스트에서 `page.route` 로 가로채 응답을 정한다.
  개발 서버는 Playwright 가 알아서 띄운다. 처음 한 번은 `npx playwright install chromium` 이 필요하다.

## 환경변수

| 변수 | 쓰는 곳 | 비워 두면 |
| --- | --- | --- |
| `VITE_BACKEND_URL` | 개발 서버 프록시, 배포 빌드의 API 주소 | 개발: `http://localhost:8080` 으로 보냄 |
| `BACKEND_SWAGGER_URL` | `npm run api:generate` 만 | 생성이 멈춤 |

값 예시는 [`.env.example`](.env.example).

## 폴더 구조

```
src/
├── pages/          화면 단위
├── components/     화면 조각. ui/ 는 여러 화면이 같이 쓰는 기본 부품
├── hooks/          화면이 쓰는 상태·데이터 조회 (대부분 React Query)
├── services/       API 호출. api.ts 의 axios 인스턴스를 거쳐야 토큰·주소가 붙는다
├── api/generated/  Orval 생성 코드 (커밋 안 함)
└── utils/          화면 표시용 변환 (라벨, 날짜, 에러 문구)
test/               단위 테스트(vitest) · e2e/(Playwright)
```

## 문서

| 문서 | 내용 |
| --- | --- |
| [`figma.md`](figma.md) | Figma 로 화면을 구현할 때 따를 규칙 (Figma MCP 기준) |
| [`../docs/에러_처리_가이드.md`](../docs/에러_처리_가이드.md) | 에러 응답 형식과 프론트가 읽는 법 |
| [`../docs/도메인_용어집.md`](../docs/도메인_용어집.md) | 도메인 용어와 화면 라벨 |
