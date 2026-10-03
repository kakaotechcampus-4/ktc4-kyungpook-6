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
docker-compose.dev.yml    dev 전용   — 인증·외부 API 환경변수
docker-compose.prod.yml   prod 전용
```

```bash
scripts/deploy.sh dev up -d --build
```

`.env` 는 **각 서버에 둡니다**(git 에 올리지 않습니다). compose 는 그 파일을 `${...}` 치환에만
쓰고, 오버라이드의 `environment:` 에 적은 것만 컨테이너에 들어갑니다.

배포는 `main`·`develop` 푸시에 `.github/workflows/deploy-main.yml`·`deploy-dev.yml` 이 돕니다.

## 브랜치와 PR

- 브랜치 이름은 `PROMPT-{번호}-{파트}-{설명}` — **이슈 키가 있어야 Jira 상태가 자동으로 움직입니다**
- 개인 작업은 `develop` 으로, 주간 멘토 리뷰는 `develop → main` 으로 올립니다

자세한 규칙은 [`docs/깃_협업_규칙.md`](docs/깃_협업_규칙.md) 와
[`docs/Jira_사용_가이드.md`](docs/Jira_사용_가이드.md) 에 있습니다.
