# 디스코드 봇 — 슬래시 명령어와 예약 메시지

`.github/workflows/notify-discord.yml` 의 웹훅은 **보내기만** 할 수 있다. 명령어를 입력받거나
예약을 접수하려면 Discord 의 요청을 **받는** 쪽이 있어야 해서 이 봇을 따로 뒀다.

## 무엇을 하나

| 명령어 | 하는 일 |
|---|---|
| `/예약 시각 내용` | 지정한 시각에 그 채널로 메시지를 보낸다 |
| `/예약목록` | 내가 건 예약을 본다 |
| `/예약취소 번호` | 내가 건 예약을 취소한다 |
| `/pr상태` | 열린 PR 을 요약해 본다 |

**입력과 응답은 전부 본인에게만 보인다**(ephemeral). 새벽에 예약을 걸어도 채널에는 아무것도
남지 않고, 지정한 시각이 되면 봇이 대신 보낸다.

받는 시각 형식: `30분후` · `2시간뒤` · `09:00` · `내일 09:00` · `10-02 09:00` · `2026-10-02 09:00`.
전부 한국 시간이다. `09:00` 처럼 날짜를 생략했는데 이미 지났으면 **내일** 같은 시각으로 본다.

## 어떻게 도나

Cloudflare Workers 다. 상시 켜 둘 PC 가 필요 없다.

- **받기** — Discord 가 슬래시 명령어를 HTTP 로 보내 준다(`src/index.ts` 의 `fetch`).
  요청마다 Ed25519 서명을 검증하고, **통과하지 못하면 401 로 거부한다**(`src/verify.ts`).
  엔드포인트는 인터넷에 열려 있으므로 이 검증이 유일한 신원 확인이다.
- **보내기** — Cron Trigger 가 1분마다 깨어나 보낼 때가 된 예약을 D1 에서 꺼내 발송한다
  (`src/index.ts` 의 `scheduled`). 발송에 실패하면 이유만 적어 두고 다음 분에 다시 시도한다.
- **저장** — D1(Workers 의 SQLite). KV 로는 "시각이 지난 것만 골라오기"가 안 돼서 SQL 쪽을 골랐다.

```
Discord ──(슬래시 명령어 + 서명)──▶ Worker.fetch ──▶ D1
                                                      │
Discord ◀──(예약 시각에 발송)──── Worker.scheduled ◀──┘  (1분마다)
```

## 지금 배포된 것

| | |
|---|---|
| Worker | `https://ktc4-discord-bot.softkleenex1217.workers.dev` |
| D1 | `ktc4-bot` (APAC) — `database_id` 는 `wrangler.toml` 에 들어 있다 |
| Cron | 1분마다 |
| Discord 앱 ID | `1555129530186731520` |
| Interactions Endpoint | 등록·검증 완료 (Discord 가 서명된 PING 을 보내 통과했다) |

확인된 것: `GET /` 200 · 서명 없는 `POST` 401 · 잘못된 서명 401 · `DELETE` 405.

**아직 남은 것은 봇 토큰이다.** 토큰이 없으면 예약 발송(cron)과 명령어 등록이 되지 않는다.

포털에서 토큰을 **복사만** 해두고:

```bash
cd bot
npm run setup:token
```

클립보드에서 토큰을 읽어(앞뒤만 보여 주고 확인을 묻는다) Cloudflare 시크릿 등록과
슬래시 명령어 등록을 이어서 한다. **터미널에 붙여넣지 않는다** — 붙여넣다 명령어 위에
떨어뜨리면 그 토큰은 셸 기록에 남아 버려야 한다.
특정 서버에 즉시 반영하려면 `DISCORD_GUILD_ID=... npm run setup:token` 으로 준다
(없으면 전역 등록이라 Discord 반영에 최대 1시간 걸린다).

토큰을 명령줄 인자가 아니라 **입력으로** 받는다 — 인자로 주면 셸 기록(`~/.zsh_history`)과
프로세스 목록(`ps`)에 그대로 보인다. 받은 값은 자식 프로세스의 stdin 으로만 흘려보낸다.

그리고 **운영진에게 서버 추가를 요청**해야 봇이 채널에 들어간다.

## 처음부터 다시 세팅할 때

봇을 돌리려면 아래 셋을 사람이 해야 한다. 코드만으로는 안 된다.

### 1. Discord 앱·봇 만들기

1. [Discord Developer Portal](https://discord.com/developers/applications) → **New Application**
2. **Bot** 탭에서 봇을 만들고 **Reset Token** 으로 토큰을 받아 둔다 (한 번만 보인다)
3. **General Information** 에서 **Application ID** 와 **Public Key** 를 복사해 둔다
4. 서버 추가는 **운영진에게 요청**한다 (봇을 만든 뒤 요청하라고 안내받았다)

### 2. Cloudflare 에 배포

```bash
cd bot
npm install

# D1 을 만들고, 출력된 database_id 를 wrangler.toml 의 PLACEHOLDER 자리에 붙인다
npx wrangler d1 create ktc4-bot
npm run db:init          # 테이블 생성 (원격)

# 시크릿 등록 — 공개 저장소라 파일에 적지 않는다
npx wrangler secret put DISCORD_PUBLIC_KEY   # 위 3번의 Public Key
npx wrangler secret put DISCORD_BOT_TOKEN    # 위 2번의 토큰
npx wrangler secret put DISCORD_APP_ID       # 위 3번의 Application ID
npx wrangler secret put GITHUB_TOKEN         # /pr상태 용 (없으면 비인증 호출로 떨어진다)

npm run deploy
```

배포하면 `https://ktc4-discord-bot.<계정>.workers.dev` 주소가 나온다. 그 주소를 Developer Portal
의 **Interactions Endpoint URL** 에 넣는다. Discord 가 일부러 잘못된 서명을 보내 401 이 나오는지
확인하고, 통과해야 저장된다 — 저장이 안 되면 `DISCORD_PUBLIC_KEY` 부터 확인한다.

### 3. 명령어 등록

```bash
DISCORD_APP_ID=... DISCORD_BOT_TOKEN=... DISCORD_GUILD_ID=... npm run register
```

`DISCORD_GUILD_ID` 를 주면 그 서버에 **즉시** 반영된다. 빼면 전역 등록이라 최대 1시간 걸린다.

## 개발

```bash
npm test          # vitest
npm run typecheck # tsc --noEmit
npm run dev       # 로컬 실행 (서명 검증 때문에 Discord 요청을 그대로 받긴 어렵다)
npm run db:init:local
```

테스트는 시각 파싱·서명 검증·명령어 처리를 덮는다. D1 과 Discord 호출은 가짜를 넣어 돌린다.

## 알아 둘 것

- **예약은 1분 단위로 발송된다.** cron 이 1분마다 도므로 지정 시각보다 최대 1분 늦을 수 있다.
- **한 사람당 예약 20건까지.** 실수로 쌓는 것을 막는 선이다.
- **남의 예약은 보이지도, 취소되지도 않는다.** 없는 번호와 남의 번호에 같은 문구로 답한다 —
  번호를 넣어 보며 다른 사람의 예약이 있는지 알아낼 수 없게 하려는 것이다.
- **예약 메시지는 `@everyone` 을 울리지 않는다.** 본문에 적혀 있어도 멘션으로 치지 않는다.
