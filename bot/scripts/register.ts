/**
 * 슬래시 명령어를 Discord 에 등록한다. 명령어를 추가·수정한 뒤 한 번 돌리면 된다.
 *
 *   DISCORD_APP_ID=... DISCORD_BOT_TOKEN=... npm run register
 *
 * 길드(서버)를 지정하면 그 서버에만 즉시 반영되고, 지정하지 않으면 전역 등록이라
 * 반영에 최대 1시간 걸린다. 개발 중에는 길드 쪽을 쓴다.
 *
 *   DISCORD_GUILD_ID=... npm run register
 */

import { COMMANDS } from "../src/commands.ts";

const appId = process.env.DISCORD_APP_ID;
const botToken = process.env.DISCORD_BOT_TOKEN;
const guildId = process.env.DISCORD_GUILD_ID;

if (!appId || !botToken) {
    console.error("DISCORD_APP_ID 와 DISCORD_BOT_TOKEN 이 필요합니다.");
    process.exit(1);
}

const url = guildId
    ? `https://discord.com/api/v10/applications/${appId}/guilds/${guildId}/commands`
    : `https://discord.com/api/v10/applications/${appId}/commands`;

const res = await fetch(url, {
    method: "PUT",
    headers: {
        authorization: `Bot ${botToken}`,
        "content-type": "application/json",
    },
    body: JSON.stringify(COMMANDS),
});

if (!res.ok) {
    console.error(`등록 실패 ${res.status}: ${await res.text()}`);
    process.exit(1);
}

const registered = (await res.json()) as Array<{ name: string }>;
console.log(
    `${guildId ? `길드 ${guildId}` : "전역"} 에 ${registered.length}개 등록: ${registered.map((c) => `/${c.name}`).join(" ")}`,
);
