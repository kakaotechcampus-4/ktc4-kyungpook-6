# ktc4-backend 실행 가이드

백엔드 서버(Spring Boot)와 데이터베이스(PostgreSQL)를 내 컴퓨터에서 띄우는 방법입니다.

**아래 명령어를 위에서부터 순서대로 복사해서 터미널에 붙여넣기만 하면 됩니다.**
각 단계마다 "이게 나오면 성공"을 적어뒀으니 그것만 확인하고 다음으로 넘어가세요.

> 맥(macOS) 기준으로 작성했습니다. 윈도우는 맨 아래 [윈도우 사용자](#윈도우-사용자) 참고.

---

## 1단계. 준비물 설치 (처음 한 번만)

세 가지가 필요합니다. 이미 깔려 있으면 건너뛰어도 됩니다.

### 1-1. JDK 21 (자바)

```bash
brew install --cask temurin@21
```

### 1-2. Gradle (빌드 도구)

```bash
brew install gradle
```

### 1-3. Docker Desktop (데이터베이스를 담는 상자)

```bash
brew install --cask docker
```

### 설치 확인

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

## 2단계. Docker Desktop 켜기

Docker는 프로그램이 실행돼 있어야 데이터베이스를 띄울 수 있습니다.

```bash
open -a Docker
```

터미널에는 아무것도 안 나옵니다. **맥 화면 위쪽 메뉴바에 고래 아이콘**이 생기고,
아이콘 애니메이션이 멈추면 준비 완료입니다. (30초~1분 걸립니다)

> 고래 아이콘에 느낌표(!)가 떠 있어도 대부분 정상입니다.
> "Resource Saver" 는 컨테이너가 없을 때 자동으로 절전하는 기능이라 그냥 두면 됩니다.

### 확인

```bash
docker ps
```

```
CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES
```

이렇게 **표 제목 줄만 나오면 성공**입니다. (아직 띄운 게 없어서 내용이 비어 있는 게 정상)

`Cannot connect to the Docker daemon` 이 나오면 아직 켜지는 중이니 30초 뒤에 다시 해보세요.

---

## 3단계. 프로젝트 폴더로 이동

clone 받은 레포 안의 `backend` 폴더로 들어갑니다.

```bash
cd ~/Desktop/KaKaoTeamproject/backend
```

> 레포를 다른 곳에 받았다면 그 경로로 바꿔주세요.
> 지금 어디인지 모르겠으면 `pwd` 를 쳐서 확인할 수 있습니다.

**아래 4~7단계는 전부 이 폴더 안에서 실행합니다.**

---

## 4단계. 데이터베이스 켜기

```bash
docker compose up -d
```

처음 실행하면 PostgreSQL 이미지를 내려받느라 **1~3분** 걸립니다. (약 400MB)
마지막에 `Container ktc4-postgres Started` 가 나오면 됩니다.

### 확인

```bash
docker ps --filter name=ktc4-postgres --format "{{.Names}}  {{.Status}}"
```

```
ktc4-postgres  Up 25 seconds (healthy)
```

**`(healthy)` 가 보여야 합니다.**
`(health: starting)` 이면 아직 준비 중이니 10초 뒤에 다시 확인하세요.

---

## 5단계. 빌드하기

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

## 6단계. 서버 실행하기

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle bootRun
```

로그가 주르륵 올라가다가 멈춘 것처럼 보이는데, **정상입니다.**
서버는 끄기 전까지 계속 켜져 있는 프로그램이라 터미널이 그대로 물려 있는 게 맞습니다.

---

## 7단계. 제대로 떴는지 확인

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

---

## 끄는 방법

### 서버 끄기

6단계를 실행한 터미널에서 키보드로 누르세요.

```
Control + C
```

### 데이터베이스 끄기

```bash
docker compose down
```

컨테이너만 내려가고 **DB에 저장된 데이터는 남아 있습니다.**
데이터까지 완전히 지우고 싶을 때만 아래를 쓰세요.

```bash
docker compose down -v
```

---

## 두 번째부터는 이것만

준비물 설치(1단계)는 다시 할 필요 없습니다. Docker Desktop이 켜져 있다면 이 두 줄이면 됩니다.

```bash
cd ~/Desktop/KaKaoTeamproject/backend && docker compose up -d
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle bootRun
```

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
| `Port 8080 was already in use` | 서버가 이미 떠 있습니다 → 기존 터미널에서 `Control + C` |

그래도 안 되면 **에러 메시지 전체를 복사해서** 팀 채널에 올려주세요.

---

## 기술 스택

| 항목 | 버전 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.4.2 |
| Gradle | 9.7.1 (8.4 이상이면 동작) |
| PostgreSQL | 16 |

**주요 의존성** — Spring Web, Spring Data JPA, Validation, Lombok, PostgreSQL Driver

## 폴더 구조

```
backend/
├── build.gradle                    라이브러리 목록·빌드 설정
├── settings.gradle                 프로젝트 이름
├── docker-compose.yml              로컬 PostgreSQL 설정
├── README.md                       이 문서
└── src/main/
    ├── java/com/ktc4/backend/
    │   └── BackendApplication.java  서버 실행 진입점
    └── resources/
        └── application.yml          DB 접속 정보·JPA 설정
```

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
