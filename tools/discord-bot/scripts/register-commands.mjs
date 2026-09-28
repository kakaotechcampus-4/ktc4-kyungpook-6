/**
 * 슬래시 명령을 디스코드에 등록한다. 명령을 고칠 때마 다시 실행한다.
 *
 *   DISCORD_APP_ID=... DISCORD_BOT_TOKEN=... npm run register
 *
 * 길드(서버) 단위로 등록하려면 DISCORD_GUILD_ID 를 같이 준다. 길드 등록은 즉시
 * 반영되고, 전역 등록은 반영에 시간이 걸릴 수 있다. 운영진 서버에 들어간 뒤
 * 그 서버 ID 로 등록하는 편이 확인이 빠르다.
 */
const appId = process.env.DISCORD_APP_ID;
const token = process.env.DISCORD_BOT_TOKEN;
const guildId = process.env.DISCORD_GUILD_ID;

if (!appId || !token) {
  console.error("DISCORD_APP_ID 와 DISCORD_BOT_TOKEN 이 필요합니다");
  process.exit(1);
}

const commands = [
  {
    name: "예약",
    description: "지정한 시각에 이 채널로 메시지를 보냅니다 (입력은 나만 보입니다)",
    options: [
      {
        name: "시각",
        description: "10:00 또는 09-30 10:00 (한국시간). 오늘 그 시각이 지났으면 내일로 잡습니다",
        type: 3,
        required: true,
      },
      { name: "내용", description: "보낼 메시지", type: 3, required: true },
    ],
  },
  {
    name: "예약목록",
    description: "내가 건 예약을 봅니다 (나만 보입니다)",
  },
  {
    name: "예약취소",
    description: "내가 건 예약을 취소합니다",
    options: [{ name: "번호", description: "예약 번호", type: 4, required: true }],
  },
  {
    name: "pr상태",
    description: "열린 PR 을 요약해서 봅니다 (나만 보입니다)",
  },
];

const url = guildId
  ? `https://discord.com/api/v10/applications/${appId}/guilds/${guildId}/commands`
  : `https://discord.com/api/v10/applications/${appId}/commands`;

const res = await fetch(url, {
  method: "PUT",
  headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" },
  body: JSON.stringify(commands),
});

if (!res.ok) {
  console.error(`등록 실패 ${res.status}`);
  console.error(await res.text());
  process.exit(1);
}
console.log(`등록 완료 (${guildId ? "길드" : "전역"}) — ${commands.length}개`);
