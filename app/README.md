# app — 점주 앱 · 아동 앱

급식카드를 쓰는 아동이 선한영향력가게를 찾아가 이용하고, 가게가 그 방문을 확인하게 하는 앱이다.
앱은 두 개이고, 한 코드에서 빌드할 때 역할만 바꿔 만든다(Capacitor + React).

- **아동 앱** — 급식카드로 갈 수 있는 가까운 가게를 찾아 주문하고, 가게에서 QR 을 보여 방문을 확인받는다
- **점주 앱** — 자기 가게를 등록·관리하고, 찾아온 아동의 QR 을 찍어 방문을 기록한다

둘 다 백엔드 API 를 부른다.

## 실행

준비물: Node 24. APK 를 만들 때만 Android Studio(안드로이드 SDK)가 더 필요하다.

```bash
cd app
npm ci
cp .env.example .env
npm run dev
```

`http://localhost:5175` 에서 열린다(관리자 웹 5173 과 겹치지 않게 고정). 개발 서버가 `/api` 요청을 `VITE_BACKEND_URL`(기본 `http://localhost:8080`)로
넘기므로, 백엔드를 먼저 띄워 둔다. 띄우는 법은 [`../backend/README.md`](../backend/README.md).

웹에서 띄우면 `VITE_APP_ROLE` 이 비어 있어 스플래시 뒤에 **역할 선택**이 뜬다. 점주는 `/owner/...`,
아동은 `/child/...` 화면으로 간다.

### 점주 앱 · 아동 앱 만들기

역할(`VITE_APP_ROLE`)은 빌드할 때 박힌다. 그래서 역할마다 따로 빌드한다.

| 만들 것 | 명령 | 결과 |
| --- | --- | --- |
| APK 두 개 | `./scripts/build-apk.sh http://<노트북 LAN 주소>:8080` | `build-apk/선한레이더-아동.apk`, `선한레이더-점주.apk` (debug 서명) |
| 웹판 두 개 | `./scripts/build-web.sh` | `build-web/child`, `build-web/owner` (`/app/child`, `/app/owner` 경로에 올리는 용도) |
| 폰에 꽂아 보며 고칠 때 | `npm run android` | 점주 앱. 아동 앱은 `npx cap run android --flavor child` |

- APK 는 폰에서 백엔드를 부르므로 `localhost` 를 쓸 수 없다. 노트북의 LAN 주소를 넘긴다.
- 웹 코드를 고친 뒤 네이티브 앱에 반영하려면 build → `cap sync` 순서로 돌린다(`npm run cap:sync`).

## 테스트

```bash
npm test      # vitest. CI 도 이 명령만 돌린다
npm run lint  # eslint. CI 에서는 돌지 않는다
```

테스트는 `test/` 에 둔다. 백엔드 없이 돈다.

## 환경변수

| 변수 | 비워 두면 |
| --- | --- |
| `VITE_BACKEND_URL` | 개발: `http://localhost:8080` 으로 보냄. 앱·웹판 빌드에서는 API 를 상대 경로(`/api/...`)로 부름 |
| `VITE_APP_ROLE` | `OWNER`·`CHILD` 가 아니면 역할 선택 화면이 뜸. 빌드 스크립트가 알아서 넣는다 |
| `VITE_KAKAO_JS_KEY` | 아동 홈 지도가 빈 채로 나옴 |
| `VITE_STORE_MOCK` | `true` 면 가게·주문·QR 조회를 백엔드 대신 목업으로 함. 비우면 백엔드를 부름 |

값 예시는 [`.env.example`](.env.example).

## 폴더 구조

```
src/
├── pages/       화면 단위. 이름이 Owner… 면 점주, Child… 면 아동 화면
├── components/  화면 조각. owner/·child/ 처럼 역할이나 기능별로 묶는다
├── hooks/       화면이 쓰는 상태·데이터 조회
├── services/    API 호출. api.ts 의 axios 인스턴스를 거쳐야 토큰·주소가 붙는다
├── utils/       표시용 변환, 입력 검사, 에러 문구
└── appRole.ts   이 빌드가 점주 앱인지 아동 앱인지
android/ · ios/  Capacitor 네이티브 프로젝트. 웹 결과물은 cap sync 가 복사해 넣는다
scripts/         APK · 웹판 빌드
test/            단위 테스트(vitest)
```

## 문서

| 문서 | 내용 |
| --- | --- |
| [`../docs/에러_처리_가이드.md`](../docs/에러_처리_가이드.md) | 에러 응답 형식과 앱이 읽는 법 |
| [`../docs/도메인_용어집.md`](../docs/도메인_용어집.md) | 도메인 용어와 화면 라벨 |
