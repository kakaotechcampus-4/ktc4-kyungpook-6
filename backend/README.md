# backend — API 서버

가게·조사·인증·QR 체크인 API 서버(Spring Boot + PostgreSQL). 관리자 웹과 앱이 이 서버를 부르고,
2차 조사는 AI 서버를 불러 맡긴다.

> 맥(macOS) 기준으로 작성했습니다. 윈도우는 맨 아래 [윈도우 사용자](#윈도우-사용자) 참고.

---

## 실행

**아래 명령어를 위에서부터 순서대로 복사해서 터미널에 붙여넣기만 하면 됩니다.**
각 단계마다 "이게 나오면 성공"을 적어뒀으니 그것만 확인하고 다음으로 넘어가세요.

### 1단계. 준비물 설치 (처음 한 번만)

세 가지가 필요합니다. 이미 깔려 있으면 건너뛰어도 됩니다.

#### 1-1. JDK 21 (자바)

```bash
brew install --cask temurin@21
```

#### 1-2. Gradle (빌드 도구)

```bash
brew install gradle
```

#### 1-3. Docker Desktop (데이터베이스를 담는 상자)

```bash
brew install --cask docker
```

#### 설치 확인

```bash
/usr/libexec/java_home -v 21
```

`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` 같은 **경로가 출력되면 성공**입니다.
`Unable to find any JVM` 이 나오면 1-1을 다시 하세요.

```bash
gradle -v
```

`Gradle 9.x` 같은 버전 정보가 나오면 성공입니다.

---

### 2단계. Docker Desktop 켜기

Docker는 프로그램이 실행돼 있어야 데이터베이스를 띄울 수 있습니다.

```bash
open -a Docker
```

터미널에는 아무것도 안 나옵니다. **맥 화면 위쪽 메뉴바에 고래 아이콘**이 생기고,
아이콘 애니메이션이 멈추면 준비 완료입니다. (30초~1분 걸립니다)

> 고래 아이콘에 느낌표(!)가 떠 있어도 대부분 정상입니다.
> "Resource Saver" 는 컨테이너가 없을 때 자동으로 절전하는 기능이라 그냥 두면 됩니다.

#### 확인

```bash
docker ps
```

```
CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES
```

이렇게 **표 제목 줄만 나오면 성공**입니다. (아직 띄운 게 없어서 내용이 비어 있는 게 정상)

`Cannot connect to the Docker daemon` 이 나오면 아직 켜지는 중이니 30초 뒤에 다시 해보세요.

---

### 3단계. 프로젝트 폴더로 이동

clone 받은 레포 안의 `backend` 폴더로 들어갑니다.

```bash
cd <레포를 받은 경로>/ktc4-kyungpook-6/backend
```

> 지금 어디인지 모르겠으면 `pwd` 를 쳐서 확인할 수 있습니다.

**아래 4~7단계는 전부 이 폴더 안에서 실행합니다.**

---

### 4단계. 데이터베이스 켜기

```bash
docker compose up -d postgres
```

> **끝의 `postgres` 를 빼먹지 마세요.** `docker-compose.yml` 에는 DB 와 함께 백엔드 컨테이너도
> 들어 있어서, `docker compose up -d` 만 치면 백엔드까지 8080 에 떠 버립니다. 그러면 6단계에서
> `Port 8080 was already in use` 가 납니다.

처음 실행하면 PostgreSQL 이미지를 내려받느라 **1~3분** 걸립니다. (약 400MB)
마지막에 `Container ktc4-postgres Started` 가 나오면 됩니다.

#### 확인

```bash
docker ps --filter name=ktc4-postgres --format "{{.Names}}  {{.Status}}"
```

```
ktc4-postgres  Up 25 seconds (healthy)
```

**`(healthy)` 가 보여야 합니다.**
`(health: starting)` 이면 아직 준비 중이니 10초 뒤에 다시 확인하세요.

---

### 5단계. 빌드하기

코드가 제대로 컴파일되는지 확인하는 단계입니다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle build
```

> 앞에 붙은 `JAVA_HOME=...` 은 **"이 명령은 자바 21로 실행해라"** 라는 뜻입니다.
> 컴퓨터에 자바가 여러 개 깔려 있어도 항상 21을 쓰게 해주므로, 매번 붙여주세요.

첫 실행은 라이브러리를 받느라 **3~7분** 걸립니다. 두 번째부터는 10초 안에 끝납니다.

```
BUILD SUCCESSFUL in 18s
```

이게 나오면 성공입니다.

---

### 6단계. 서버 실행하기

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle bootRun
```

로그가 주르륵 올라가다가 멈춘 것처럼 보이는데, **정상입니다.**
서버는 끄기 전까지 계속 켜져 있는 프로그램이라 터미널이 그대로 물려 있는 게 맞습니다.

---

### 7단계. 제대로 떴는지 확인

6단계 로그에서 아래 **4가지를 눈으로 확인**하세요.

| 확인할 것 | 로그에서 찾을 문구 |
| --- | --- |
| 자바 버전이 21인가 | `Starting BackendApplication using Java 21.0.6` |
| **DB에 연결됐는가** | `HikariPool-1 - Start completed` |
| **DB가 PostgreSQL 16인가** | `Database version: 16.15` |
| 서버가 다 떴는가 | `Started BackendApplication in 0.986 seconds` |

가장 중요한 건 **`HikariPool-1 - Start completed`** 입니다.
빌드가 성공해도 DB 연결은 실패할 수 있기 때문에, 이 줄이 있어야 진짜 성공입니다.

네 줄이 다 보이면 **세팅 완료**입니다. 서버는 `http://localhost:8080` 에서 돌고 있습니다.

API 목록은 Swagger 에서 볼 수 있습니다: `http://localhost:8080/swagger-ui/index.html`
(작성법은 [`docs/스웨거_API_문서화_가이드.md`](docs/스웨거_API_문서화_가이드.md))

> DB 표는 서버가 뜰 때 Flyway 가 `src/main/resources/db/migration/` 의 SQL 로 만듭니다.
> 엔티티를 고쳤는데 마이그레이션을 안 쓰면 기동이 멈춥니다(`ddl-auto: validate`).

---

### 끄는 방법

#### 서버 끄기

6단계를 실행한 터미널에서 키보드로 누르세요.

```
Control + C
```

#### 데이터베이스 끄기

```bash
docker compose down
```

컨테이너만 내려가고 **DB에 저장된 데이터는 남아 있습니다.**
데이터까지 완전히 지우고 싶을 때만 아래를 쓰세요.

```bash
docker compose down -v
```

---

### 두 번째부터는 이것만

준비물 설치(1단계)는 다시 할 필요 없습니다. Docker Desktop이 켜져 있다면 이 두 줄이면 됩니다.

```bash
docker compose up -d postgres
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle bootRun
```

---

## 테스트

```bash
./scripts/test.sh                                  # 전체
./scripts/test.sh --tests '*BusinessServiceTest'   # 특정 테스트만
```

맥에서는 자바 21 을 알아서 골라 `gradle test` 를 돌립니다. CI 도 같은 스크립트를 씁니다.

**Docker Desktop 이 켜져 있어야 합니다.** DB 를 쓰는 테스트는 Testcontainers 가 테스트용 PostgreSQL 을
따로 띄웁니다(4단계의 DB 와는 별개).

---

## 환경변수

로그인·관리자 계정·외부 API 를 로컬에서 써 보려면 값을 넣어야 합니다. 넣지 않아도 서버는 뜹니다.

| 변수 | 비워 두면 |
| --- | --- |
| `JWT_SECRET` | 재시작할 때마다 로그인이 풀림 |
| `ADMIN_EMAIL` · `ADMIN_PASSWORD` | 관리자 계정이 만들어지지 않음 |
| `CARD_HASH_SECRET` | 아동 카드 인증만 503 |
| `INTERNAL_API_KEY` | AI 서버가 백엔드 조회 API 를 부를 수 없고, 백엔드가 AI 를 부를 때 키를 싣지 않음 |
| `AI_BASE_URL` | `http://localhost:8000` 의 AI 서버를 찾음 |
| `NTS_SERVICE_KEY` · `BIZNO_API_KEY` · `KAKAO_REST_API_KEY` | 국세청 대조 · 비즈노 조회 · 좌표 채우기가 동작하지 않음 |

각 값의 설명은 [`.env.example`](.env.example) 에 있습니다. 전체 목록은 `src/main/resources/application.yml` 의
`${...}` 를 보세요.

> **`gradle bootRun` 은 `.env` 파일을 읽지 않습니다.** `.env` 는 `docker compose` 만 읽습니다.
> 직접 띄울 때는 터미널에서 넘겨 주세요.
>
> ```bash
> JWT_SECRET=... ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD=... \
>   JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle bootRun
> ```

---

## 폴더 구조

```
backend/
├── build.gradle                    라이브러리 목록·빌드 설정
├── settings.gradle                 프로젝트 이름
├── docker-compose.yml              로컬 PostgreSQL (+ 백엔드 컨테이너)
├── Dockerfile                      백엔드 이미지 빌드
├── .env.example                    docker compose 용 환경변수 예시
├── scripts/test.sh                 테스트 실행 (CI 도 사용)
├── docs/                           백엔드 가이드 (아래 목록)
└── src/
    ├── main/
    │   ├── java/com/ktc4/backend/
    │   │   ├── BackendApplication.java  서버 실행 진입점
    │   │   ├── domain/                  기능별 패키지 (store, job, task, member, checkin …)
    │   │   └── global/                  공통 설정·보안·에러 처리
    │   └── resources/
    │       ├── application.yml          설정 (환경변수로 덮어씀)
    │       └── db/migration/            Flyway 마이그레이션 SQL
    └── test/                            단위·통합 테스트
```

패키지를 나누는 규칙은 [`docs/백엔드_아키텍처_가이드.md`](docs/백엔드_아키텍처_가이드.md) 를 보세요.

## 문서

| 문서 | 내용 |
| --- | --- |
| [`백엔드_코딩_컨벤션_가이드.md`](docs/백엔드_코딩_컨벤션_가이드.md) | 네이밍, Lombok, 예외 처리, 로깅 |
| [`백엔드_아키텍처_가이드.md`](docs/백엔드_아키텍처_가이드.md) | 도메인 패키지 구조 |
| [`Javadoc_가이드.md`](docs/Javadoc_가이드.md) | Javadoc 작성 대상과 형식 |
| [`스웨거_API_문서화_가이드.md`](docs/스웨거_API_문서화_가이드.md) | Swagger 사용법 |
| [`데이터_정규화_가이드.md`](docs/데이터_정규화_가이드.md) | 사업자등록번호·가게 이름·주소 정규화 |

에러 응답 형식은 팀 공통 문서 [`../docs/에러_처리_가이드.md`](../docs/에러_처리_가이드.md) 에 있습니다.

---

## 기술 스택

| 항목 | 버전 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.4.2 |
| Gradle | 9.7.1 (8.4 이상이면 동작) |
| PostgreSQL | 16 |

**주요 의존성** — Spring Web, Spring Data JPA, Validation, Spring Security + JJWT, springdoc(Swagger),
Flyway, Lombok, PostgreSQL Driver. 테스트는 Spring Boot Test, Spring Security Test, Testcontainers

---

## 데이터베이스를 직접 들여다보고 싶을 때

DBeaver, IntelliJ Database, TablePlus 같은 도구로 붙을 수 있습니다.

| 항목 | 값 |
| --- | --- |
| Host | `localhost` |
| Port | `5432` |
| Database | `ktc4` |
| User | `ktc4` |
| Password | `ktc4local` |

> 이 계정은 **각자 컴퓨터 안에서만** 쓰는 개발용입니다.
> 외부에서는 접속이 불가능하게 막혀 있어서 공개돼 있어도 괜찮습니다.
> 다만 **실제 서버의 DB 주소나 비밀번호는 절대 이 파일이나 `docker-compose.yml` 에 적으면 안 됩니다.**
> 이 저장소는 public 이라 누구나 볼 수 있습니다.

---

## 잘 안 될 때

| 이런 메시지가 나오면 | 이렇게 하세요 |
| --- | --- |
| `Cannot connect to the Docker daemon` | Docker Desktop이 안 켜졌습니다 → 2단계 다시 |
| `Connection refused` (5432) | DB가 안 떠 있습니다 → 4단계 다시 |
| `Unsupported class file major version` | 자바 버전 문제입니다 → 명령어 앞에 `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 를 붙였는지 확인 |
| `Unable to find any JVM` | JDK 21이 없습니다 → 1-1 다시 |
| `command not found: gradle` | Gradle이 없습니다 → 1-2 다시 |
| `Port 8080 was already in use` | 서버가 이미 떠 있습니다 → 기존 터미널에서 `Control + C`. 백엔드 컨테이너가 떠 있으면 `docker compose stop backend` |

그래도 안 되면 **에러 메시지 전체를 복사해서** 팀 채널에 올려주세요.

---

## 윈도우 사용자

기본 흐름은 같지만 두 가지가 다릅니다.

1. **설치** — `brew` 대신 각 공식 사이트에서 설치하거나 `winget` 을 사용하세요.
   - JDK 21: [Adoptium](https://adoptium.net/)
   - Gradle: [gradle.org](https://gradle.org/install/)
   - Docker Desktop: [docker.com](https://www.docker.com/products/docker-desktop/)

2. **자바 버전 지정** — `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 은 맥 전용 문법입니다.
   PowerShell에서는 아래처럼 하세요.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21"
gradle build
```

3. **테스트** — `scripts/test.sh` 는 bash 스크립트입니다. Git Bash 에서 돌리거나, PowerShell 에서는
   위처럼 `JAVA_HOME` 을 맞춘 뒤 `gradle test` 를 직접 치세요.
