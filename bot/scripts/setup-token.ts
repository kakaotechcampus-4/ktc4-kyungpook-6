/**
 * 봇 토큰을 **한 번만** 붙여넣으면 나머지를 한다.
 *
 *   npm run setup:token
 *
 * 1. Cloudflare 에 `DISCORD_BOT_TOKEN` 시크릿으로 넣는다
 * 2. 슬래시 명령어를 등록한다
 *
 * 토큰은 입력받아 자식 프로세스의 stdin 으로만 흘려보낸다 — 명령줄 인자로 주면
 * 셸 기록(`~/.zsh_history`)과 프로세스 목록(`ps`)에 그대로 보인다.
 */

import { createInterface } from "node:readline/promises";
import { spawn } from "node:child_process";

import { COMMANDS } from "../src/commands.ts";

const APP_ID = process.env.DISCORD_APP_ID ?? "1555129530186731520";
const guildId = process.env.DISCORD_GUILD_ID;

/** 자식 프로세스를 돌리고 stdin 으로 값을 넣어 준다. */
function run(cmd: string, args: string[], stdinValue?: string): Promise<number> {
    return new Promise((resolve, reject) => {
        const child = spawn(cmd, args, { stdio: [stdinValue ? "pipe" : "inherit", "inherit", "inherit"] });
        if (stdinValue && child.stdin) {
            child.stdin.write(stdinValue);
            child.stdin.end();
        }
        child.on("error", reject);
        child.on("close", (code) => resolve(code ?? 1));
    });
}

/** 토큰처럼 생겼는지 본다. Discord 봇 토큰은 `.` 로 나뉜 세 토막이다. */
function looksLikeToken(v: string): boolean {
    return /^[\w-]{20,}\.[\w-]{5,}\.[\w-]{20,}$/.test(v);
}

/** 앞뒤만 남기고 가린다. 확인용이라 전체를 보여줄 이유가 없다. */
function mask(v: string): string {
    return `${v.slice(0, 6)}…${v.slice(-4)} (${v.length}자)`;
}

/**
 * **클립보드에서 먼저 읽는다.** 터미널에 붙여넣다가 명령어 위에 떨어뜨리면 그 토큰은
 * 셸 기록에 남아 쓸 수 없게 된다 — 실제로 겪은 일이다. 포털에서 복사한 직후 이 명령만
 * 치면 붙여넣을 일이 없다.
 */
async function readToken(rl: ReturnType<typeof createInterface>): Promise<string> {
    const fromEnv = process.env.DISCORD_BOT_TOKEN?.trim();
    if (fromEnv) return fromEnv;

    const clip = await new Promise<string>((resolve) => {
        const child = spawn("pbpaste", [], { stdio: ["ignore", "pipe", "ignore"] });
        let out = "";
        child.stdout?.on("data", (c) => (out += c));
        child.on("close", () => resolve(out.trim()));
        child.on("error", () => resolve(""));
    });

    if (looksLikeToken(clip)) {
        const ok = await rl.question(`클립보드에서 토큰을 찾았습니다: ${mask(clip)}\n이걸로 진행할까요? [Y/n] `);
        if (ok.trim().toLowerCase() !== "n") return clip;
    } else if (clip) {
        console.log("클립보드 내용이 토큰 형식이 아닙니다.");
    }

    console.log("포털에서 토큰을 복사한 뒤 이 명령을 다시 실행하면 자동으로 읽습니다.");
    return (await rl.question("또는 지금 붙여넣고 엔터: ")).trim();
}

const rl = createInterface({ input: process.stdin, output: process.stdout });
const token = await readToken(rl);
rl.close();

if (!token) {
    console.error("토큰이 비어 있습니다.");
    process.exit(1);
}
if (!looksLikeToken(token)) {
    console.error("토큰 형식이 아닙니다. 포털 → 봇 → 토큰 초기화 로 받은 값을 복사해 주세요.");
    process.exit(1);
}

console.log("\n[1/2] Cloudflare 에 시크릿 등록 중...");
const secretCode = await run("npx", ["wrangler", "secret", "put", "DISCORD_BOT_TOKEN"], token);
if (secretCode !== 0) {
    console.error("시크릿 등록에 실패했습니다. `npx wrangler whoami` 로 로그인 상태를 확인해 주세요.");
    process.exit(1);
}

console.log("\n[2/2] 슬래시 명령어 등록 중...");
const url = guildId
    ? `https://discord.com/api/v10/applications/${APP_ID}/guilds/${guildId}/commands`
    : `https://discord.com/api/v10/applications/${APP_ID}/commands`;

const res = await fetch(url, {
    method: "PUT",
    headers: { authorization: `Bot ${token}`, "content-type": "application/json" },
    body: JSON.stringify(COMMANDS),
});

if (!res.ok) {
    console.error(`명령어 등록 실패 ${res.status}: ${(await res.text()).slice(0, 300)}`);
    console.error("토큰이 맞는지(초기화 후 새 값인지) 확인해 주세요.");
    process.exit(1);
}

const registered = (await res.json()) as Array<{ name: string }>;
console.log(
    `\n✅ 끝났습니다. ${guildId ? `길드 ${guildId}` : "전역"} 에 ${registered.length}개 등록: ` +
        registered.map((c) => `/${c.name}`).join(" "),
);
if (!guildId) console.log("   전역 등록은 Discord 반영에 최대 1시간 걸립니다.");
console.log("   남은 것: 운영진에게 봇 서버 추가 요청");
