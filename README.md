# ktc4-kyungpook-6

카카오테크 캠퍼스 4기 2단계 팀 프로젝트 — 경북대 6팀

결식아동 급식카드 가맹점의 **정보가 낡는 문제**를 다룹니다. 공개 정보로 가게 상태 변화를
찾아 담당자에게 **수정안**을 제안하고, 점주가 아동의 방문을 QR 로 확인합니다.

작업을 시작하기 전에 [`CLAUDE.md`](CLAUDE.md) 의 컨벤션과 문서 목록을 먼저 보세요.

## 저장소 구조

```
backend/    Spring Boot · Java 21     가게·조사·인증·QR 체크인 API
ai/         FastAPI · Python          가게 정보 2차 조사 (웹검색·카카오맵·국세청)
frontend/   React · Vite              관리자 웹 (가게 목록, 조사 결과 검토)
app/        Capacitor · React         점주용 앱 (QR 스캔) — 아직 껍데기
docs/       팀 공통 문서              용어집, 깃·Jira 규칙, 에러 규격
scripts/    deploy.sh                 compose 파일을 묶어 배포
.github/    워크플로                  테스트, 배포, 멘토 자동 배정, 디스코드 알림
```

파트별 상세는 각 폴더의 `README.md` 와 `docs/` 를 보세요
([`backend/README.md`](backend/README.md) · [`ai/README.md`](ai/README.md)).

## 실행과 테스트

각 파트는 **자기 폴더에서** 돌립니다. CI(`.github/workflows/test.yml`)도 같은 스크립트를 씁니다.

| 파트 | 준비 | 테스트 |
|---|---|---|
| `backend/` | `brew install gradle` (Java 21) | `./scripts/test.sh` → `gradle test` |
| `ai/` | `uv sync` | `./scripts/test.sh` → `uv run pytest tests/` |
| `frontend/` | `npm ci` | `./scripts/test.sh` → `npm run lint` |
| `app/` | `npm ci` | `npm test` (테스트 파일은 아직 없음) |

**백엔드는 Gradle wrapper 를 쓰지 않습니다.** `brew install gradle` 로 설치한 Gradle 을 쓰고,
배포는 `gradle:8-jdk21` 이미지로, CI 는 러너의 Gradle 로 빌드합니다. `./gradlew` 를 부르는
스크립트는 없습니다(`app/android/` 의 래퍼는 Capacitor 가 만든 안드로이드 프로젝트 소유입니다).

AI 서버를 직접 띄우려면:

```bash
cd ai && uv run uvicorn src.server.main:app --reload --port 8000
```

## 로컬에서 전체 띄우기

```bash
cp .env.example .env     # 값을 채운다
docker compose up -d --build
```

## 배포

compose 파일이 셋입니다. **base 위에 환경별 오버라이드를 얹는 구조**라 직접 부르지 말고
`scripts/deploy.sh` 를 쓰세요.

```
docker-compose.yml        base       — 모든 환경 공통
docker-compose.dev.yml    dev 전용   — 인증·외부 API 환경변수, DB·AI 포트 공개
docker-compose.prod.yml   prod 전용
```

```bash
scripts/deploy.sh dev up -d --build
```

`.env` 는 **각 서버에 둡니다**(git 에 올리지 않습니다). compose 는 그 파일을 `${...}` 치환에만
쓰고, 오버라이드의 `environment:` 에 적은 것만 컨테이너에 들어갑니다.

dev 서버에서만 쓰는 값은 `.env.development` 에 따로 둡니다(`.env.development.example` 참고).
`scripts/deploy.sh dev` 가 `.env` 위에 얹어 읽고, 같은 키는 `.env.development` 가 이깁니다.
파일이 없으면 `.env` 만 읽습니다.

| 값 | 설명 |
|---|---|
| `POSTGRES_PORT` | DB 를 바깥에 여는 포트. 비어 있으면 dev 배포가 멈춥니다 |
| `AI_PORT` | AI 서버를 바깥에 여는 포트(기본 8000). AI 포트를 열기 때문에 dev 는 `.env` 의 `INTERNAL_API_KEY` 가 비어 있으면 배포가 멈춥니다 |
| `AI_BASE_URL` | 백엔드가 부를 AI 주소(기본 `http://ai:8000`). base·prod 는 이 주소로 고정이라 dev 에서만 바꿀 수 있습니다 |

백엔드 주소는 **쓰임이 달라 키가 둘**입니다. 한 키를 두 뜻으로 쓰다가 10주차 멘토 리뷰에서 지적받아 갈랐습니다.

| 값 | 누가 읽나 | 언제 박히나 |
|---|---|---|
| `PUBLIC_BACKEND_URL` | 브라우저 — 공개망 주소입니다 | frontend 를 빌드할 때 |
| `BACKEND_BASE_URL` | AI 컨테이너 — dev 에서만 바꿉니다 | 컨테이너가 뜰 때 |

`PUBLIC_` 접두사는 "여기 들어간 값은 전 세계가 본다" 는 뜻입니다. 내부망 주소를 넣지 마세요.

서버 `.env` 에 `PUBLIC_BACKEND_URL` 이 없으면 옛 키(`BACKEND_BASE_URL`)로 떨어집니다 — 서버에 키를 넣기
전에 머지돼도 화면이 죽지 않게 둔 폴백입니다. 서버 `.env` 에 키가 들어가면 `docker-compose.yml` 에서 그
폴백을 지우세요.

⚠️ dev 의 AI 컨테이너는 **지금 공개 주소로 백엔드를 부릅니다**(서버 `.env` 에 들어 있는 값이 그것입니다).
내부망 `http://backend:8080` 으로 돌리는 쪽이 맞지만, 호출 경로가 달라지므로 배포본에서 조사가 도는지
확인한 뒤에 바꿉니다. `http://localhost:8080` 은 AI 컨테이너 안에서 자기 자신을 가리키니 넣지 마세요.

### AI 조사에 필요한 값

조사는 **Vertex AI** 로 돕니다. 키가 없으면 **배포는 성공하고 화면도 뜨는데 조사만 503** 이라 티가
안 납니다(`ai/src/server/main.py` 가 `GOOGLE_CLOUD_PROJECT` 가 없으면 조사기를 안 꽂습니다).

Vertex 는 API 키가 아니라 **서비스 계정 JSON(ADC)** 으로 인증합니다. 배포에서는 저장소 시크릿에서
받아 붙입니다 — **서버 `.env` 를 고치지 않습니다.**

```
GitHub Secrets (GCP_SA_KEY)  →  deploy-dev.yml 이 ~/.ktc4/vertex.json 으로 씀
                             →  VERTEX_KEY_PATH  →  컨테이너 /run/secrets/vertex.json
```

| 이름 | 종류 | 없으면 |
|---|---|---|
| `GCP_SA_KEY` · `GCP_PROJECT_ID` | Secret | 조사가 503 |
| `KAKAO_REST_API_KEY` | Secret | 지도 확인 없이 웹검색만 |
| `NAVER_CLIENT_ID` · `NAVER_CLIENT_SECRET` | Secret | 네이버 대조 불가 |
| `AGENT_API_KEY` | Secret | 판단 모델이 Vertex 로 떨어집니다 |
| `AI_NAVER_LOCAL_ENABLED` · `AI_KAKAO_COMPARE_ENABLED` · `AI_AGENT_ENABLED` · `AI_AGENT_MODEL` | Variable | 전부 꺼짐 |

지도 대조를 막던 상호 자르기 버그는 `3e541c6`(2026-10-09)에서 고쳐졌습니다 —
`"대구왕갈비본점"` 은 그대로 두고 `"크라운호프대구수성못점"` 의 지점명 앞 지역어만 뗍니다.

⚠️ **네이버는 켜지 않습니다.** 장소 페이지 자동 접근이 막혀 있고 약관상 저장도 안 됩니다
(12곳 모두 차단 — `#120` 측정). 카카오만 쓰되 **실시간 비교 후 폐기**합니다.

배포는 `main`·`develop` 푸시에 `.github/workflows/deploy-main.yml`·`deploy-dev.yml` 이 돕니다.
**main 쪽은 `workflow_dispatch` 로 손으로도 돌릴 수 있습니다** — 그게 없으면 주간 멘토 리뷰 PR 이
머지될 때만 돌아서, 배포를 고쳐도 다음 주까지 시험할 방법이 없었습니다.

## 브랜치와 PR

- 브랜치 이름은 `PROMPT-{번호}-{파트}-{설명}` — **이슈 키가 있어야 Jira 상태가 자동으로 움직입니다**
- 개인 작업은 `develop` 으로, 주간 멘토 리뷰는 `develop → main` 으로 올립니다

자세한 규칙은 [`docs/깃_협업_규칙.md`](docs/깃_협업_규칙.md) 와
[`docs/Jira_사용_가이드.md`](docs/Jira_사용_가이드.md) 에 있습니다.
