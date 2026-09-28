# 디스코드 봇 — 슬래시 명령어와 예약 메시지

팀 디스코드에서 쓰는 봇이다. **웹훅으로는 안 되는 것만** 여기서 한다.

| | 웹훅 (`.github/workflows/notify-discord-team.yml`) | 봇 (이 폴더) |
|---|---|---|
| PR 열림·머지 알림 | ✅ | — |
| 방치된 PR 매시간 점검 | ✅ (`remind-discord-team.yml`) | — |
| **디코에서 명령어로 조회** | ❌ 불가 | ✅ `/pr상태` |
| **예약 메시지** | ❌ 불가 (입력을 받을 수 없다) | ✅ `/예약` |

알림은 이미 웹훅이 하고 있으니 봇으로 옮기지 않는다. 봇은 **받는 일**만 맡는다.

## 명령어

| 명령 | 하는 일 |
|---|---|
| `/예약 시각:10:00 내용:...` | 그 시각에 **이 채널로** 보낸다 |
| `/예약목록` | 내가 건 예약을 본다 |
| `/예약취소 번호:12` | 내가 건 예약을 취소한다 |
| `/pr상태` | 열린 PR 을 요약해서 본다 |

**입력은 전부 나만 보인다.** 디스코드의 ephemeral 응답(`flags: 64`)을 쓴다. 새벽 3시에
`/예약 시각:10:00 내용:오늘 회의 10시입니다` 를 쳐도 채널에는 아무것도 남지 않고, 10시에
봇이 그 내용을 채널에 올린다. 예약을 걸었다는 사실도 그때까지 아무에게도 보이지 않는다.

시각은 한국시간으로 읽는다. `10:00` 처럼 시간만 주면 오늘 그 시각이고, **이미 지났으면
내일로 잡는다**(새벽에 `10:00` 이라 치면 몇 시간 뒤가 된다). `09-30 10:00` 이나
`2026-09-30 10:00` 도 된다.

## 왜 Cloudflare Workers 인가

예약 발송은 그 시각에 뭔가 깨어 있어야 한다. 상시 켜둘 PC 를 두는 대신,

- 슬래시 명령어는 디스코드의 **HTTP Interactions 엔드포인트**로 받는다 — 게이트웨이 상시 연결이 필요 없다
- 예약 발송은 **Cron Trigger** 가 1분마다 D1(SQLite)을 확인해서 보낸다

그래서 팀 dev 서버(self-hosted runner PC)가 꺼져도 예약이 날아가지 않고, 배포 compose 를
건드리지 않아 인프라 파트 작업과 섞이지 않는다.

## 처음 세팅 (사람이 해야 하는 것)

### 1. 디스코드 앱·봇 만들기

1. https://discord.com/developers/applications → **New Application**
2. **Bot** 탭 → 토큰 발급 (`DISCORD_BOT_TOKEN`). **한 번만 보여주니 바로 옮겨 적는다**
3. **General Information** 의 `APPLICATION ID`(`DISCORD_APP_ID`)와 `PUBLIC KEY`(`DISCORD_PUBLIC_KEY`)를 적어둔다
4. 권한은 **`Send Messages` 만** 있으면 된다. 메시지 내용 인텐트(Message Content Intent)는 **켜지 않는다** — 슬래시 명령만 쓰므로 채널 대화를 읽을 필요가 없다

### 2. 운영진에게 서버 추가 요청

운영진 안내가 *"봇은 만드신 후에 추가 요청 주시면 되고"* 였다. 초대 URL 을 만들어 그 스레드에 올린다.

```
https://discord.com/oauth2/authorize?client_id=<DISCORD_APP_ID>&scope=bot+applications.commands&permissions=2048
```
`permissions=2048` 이 `Send Messages` 다. 더 넓은 권한을 요청하지 않는다.

### 3. Cloudflare 배포

```bash
cd tools/discord-bot
npm install

# D1 만들고, 출력된 database_id 를 wrangler.toml 에 적는다
npx wrangler d1 create ktc4-kyungpook-6-bot
npm run db:init            # schema.sql 적용

# 시크릿 (wrangler.toml 에 적지 않는다)
npx wrangler secret put DISCORD_PUBLIC_KEY
npx wrangler secret put DISCORD_BOT_TOKEN
npx wrangler secret put GITHUB_TOKEN        # /pr상태 용. repo 읽기 권한만

npm run deploy             # 배포되면 URL 이 나온다
```

### 4. 디스코드에 엔드포인트와 명령 등록

1. Developer Portal → **General Information** → `INTERACTIONS ENDPOINT URL` 에 배포된 Worker URL 을 넣고 저장
   - 저장을 누르면 디스코드가 **일부러 틀린 서명으로도 찔러본다.** 그걸 401 로 막아야 저장이 통과한다 (`src/verify.js`)
2. 명령 등록
   ```bash
   DISCORD_APP_ID=... DISCORD_BOT_TOKEN=... DISCORD_GUILD_ID=<서버ID> npm run register
   ```
   서버 ID 를 주면 즉시 반영된다. 안 주면 전역 등록이라 반영에 시간이 걸린다.

## 명령을 추가할 때 (나중에)

예약 말고도 붙일 게 생기면 **두 곳만 고치면 된다.** 나머지 구조는 손댈 필요가 없다.

1. `scripts/register-commands.mjs` 의 `commands` 배열에 정의를 넣고 `npm run register`
2. `src/index.js` 의 `switch (interaction.data?.name)` 에 `case` 를 추가

한 가지만 지킨다 — **3초 안에 답해야 한다.** 디스코드는 인터랙션에 3초 내 응답을 요구한다.
오래 걸리는 일(여러 API 를 훑는 등)이면 먼저 "생각 중" 응답(`type: 5`)을 보내고 나중에
`PATCH /webhooks/{app_id}/{token}/messages/@original` 로 채우는 방식으로 가야 한다.
지금 명령들은 전부 질의 한두 번이라 그럴 필요가 없다.

떠오른 후보 (정하지 않았다): 반복 예약 · `/지라` 로 내 티켓 보기 · 배포 상태 조회 ·
리뷰 안 한 사람 조용히 찔러보기(본인만 보이는 알림).

## 확인

```bash
npm test          # 22개. 시각 해석 · 서명 검증 · 중복 발송 방지
npm run dev       # 로컬에서 Worker 를 띄운다 (D1 은 --remote 없이 로컬 것을 쓴다)
```

테스트는 외부 호출 없이 돈다 — `fetch` 를 갈아끼우고 D1 은 흉내 낸 것을 쓴다.

## 주의한 것

**서명 검증을 건너뛰지 않는다.** 이 엔드포인트는 인터넷에 열려 있다. 검증이 없으면 누구나
우리 봇 이름으로 예약을 걸 수 있다. 헤더가 없는 요청, hex 가 아닌 서명, 길이가 안 맞는 키도
전부 401 이다.

**같은 예약을 두 번 보내지 않는다.** 먼저 `sent_at` 을 찍어 그 행을 잡고, UPDATE 가 실제로
한 행을 바꿨을 때만 발송한다. 순서를 반대로 하면(보내고 나서 표시) 표시가 실패할 때 다음
분에 또 나간다 — 중복은 되돌릴 수 없으니 이쪽을 택했다. 발송이 실패하면 표시를 되돌리고
시도 횟수를 올려서 **3회까지만** 다시 시도한다.

**남의 예약은 못 본다.** `/예약목록`·`/예약취소` 는 질의 조건에 `user_id` 가 들어가 있다.

## 아직 안 한 것

- **반복 예약**(매주 월요일 같은 것)이 없다. 한 번짜리만 된다
- 예약 내용을 **고치는** 명령이 없다. 취소하고 다시 건다
- 채널을 골라 보낼 수 없다. **명령을 입력한 채널**로 간다
- 예약이 오래 밀려도 경고하지 않는다. 3회 실패한 예약은 조용히 남는다 (D1 을 직접 봐야 안다)
