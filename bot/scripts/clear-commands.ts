/**
 * 중복으로 등록된 슬래시 명령어를 지운다.
 *
 *   npm run clear:guild     # 길드 등록만 지운다 (전역은 남긴다) ← 보통 이것
 *   npm run clear:global    # 전역 등록만 지운다
 *
 * **왜 필요한가** — 전역(`/applications/{id}/commands`)과 길드
 * (`/applications/{id}/guilds/{gid}/commands`)에 같은 이름을 둘 다 등록하면
 * 디스코드가 둘 다 보여준다. `/` 를 누르면 `/예약` 이 두 번 뜬다.
 *
 * **왜 길드 쪽을 지우는가** — 전역 명령어는 DM 에서도 되고, 나중에 다른 서버에
 * 추가해도 그대로 동작한다. 길드 등록은 그 서버에서만 쓸 수 있다.
 * (길드 등록은 반영이 즉시라는 장점이 있어, 처음 붙일 때만 쓰고 지우면 된다.)
 *
 * 토큰은 setup-token.ts 와 같은 방식으로 클립보드에서 읽는다 — 명령줄 인자로 주면
 * 셸 기록(`~/.zsh_history`)과 프로세스 목록(`ps`)에 그대로 보인다.
 */

import { spawn } from "node:child_process";

const APP_ID = process.env.DISCORD_APP_ID ?? "1555129530186731520";
const GUILD_ID = process.env.DISCORD_GUILD_ID ?? "1495976100319330314";
const scope = process.argv[2] === "global" ? "global" : "guild";

function looksLikeToken(v: string): boolean {
    return /^[\w-]{20,}\.[\w-]{5,}\.[\w-]{20,}$/.test(v);
}

function mask(v: string): string {
    return `${v.slice(0, 6)}…${v.slice(-4)} (${v.length}자)`;
}

function readClipboard(): Promise<string> {
    return new Promise((resolve) => {
        const child = spawn("pbpaste", [], { stdio: ["ignore", "pipe", "ignore"] });
        let out = "";
        child.stdout?.on("data", (c) => (out += c));
        child.on("close", () => resolve(out.trim()));
        child.on("error", () => resolve(""));
    });
}

const token = process.env.DISCORD_BOT_TOKEN?.trim() || (await readClipboard());
if (!looksLikeToken(token)) {
    console.error("클립보드에 **디스코드 봇 토큰**이 없습니다. (GitHub 토큰이 아닙니다)");
    console.error("포털 → 애플리케이션 → 사랑이 → 봇 → 토큰 재설정 → 복사한 뒤 다시 실행해 주세요.");
    console.error("  https://discord.com/developers/applications/" + APP_ID + "/bot");
    console.error("⚠️ 토큰을 재설정하면 Cloudflare 에 든 기존 토큰이 무효가 됩니다.");
    console.error("   그 경우 `npm run setup:token` 을 먼저 돌려 시크릿을 갱신하세요.");
    process.exit(1);
}
console.log(`토큰을 읽었습니다: ${mask(token)}`);

const base = `https://discord.com/api/v10/applications/${APP_ID}`;
const url = scope === "guild" ? `${base}/guilds/${GUILD_ID}/commands` : `${base}/commands`;
const headers = { authorization: `Bot ${token}`, "content-type": "application/json" };

// 지우기 전에 무엇이 있는지 보여준다. 빈 배열을 PUT 하면 되돌릴 수 없다.
const before = await fetch(url, { headers });
if (!before.ok) {
    console.error(`조회 실패 ${before.status}: ${(await before.text()).slice(0, 200)}`);
    process.exit(1);
}
const list = (await before.json()) as Array<{ name: string }>;
console.log(`\n${scope === "guild" ? `길드 ${GUILD_ID}` : "전역"} 에 등록된 명령어 ${list.length}개:`);
console.log(list.length ? "  " + list.map((c) => `/${c.name}`).join(" ") : "  (없음)");

if (list.length === 0) {
    console.log("\n지울 것이 없습니다.");
    process.exit(0);
}

const res = await fetch(url, { method: "PUT", headers, body: "[]" });
if (!res.ok) {
    console.error(`\n삭제 실패 ${res.status}: ${(await res.text()).slice(0, 300)}`);
    process.exit(1);
}
console.log(`\n✅ ${scope === "guild" ? "길드" : "전역"} 등록을 지웠습니다.`);
console.log(scope === "guild"
    ? "   전역 등록은 그대로입니다. 디스코드를 새로고침하면 중복이 사라집니다."
    : "   길드 등록은 그대로입니다.");
