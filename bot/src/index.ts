/**
 * 디스코드 봇 진입점 (Cloudflare Workers).
 *
 * 두 개의 입구가 있다.
 * - `fetch` — Discord 가 슬래시 명령어를 HTTP 로 보내 준다. 서명을 검증하고 명령어별로 넘긴다.
 * - `scheduled` — 1분마다 깨어나 보낼 때가 된 예약을 발송한다.
 *
 * 웹훅(`.github/workflows/notify-discord.yml`)은 보내기만 할 수 있어서 명령어 입력을
 * 받지 못한다. 그래서 받는 쪽을 따로 둔 것이다.
 */

import { COMMANDS } from "./commands.ts";
import {
    deferEphemeral,
    ephemeral,
    followUp,
    InteractionResponseType,
    InteractionType,
    sendToChannel,
} from "./discord.ts";
import { handleCancel, handleList, handlePrStatus, handleReserve, type Db, type Reservation } from "./handlers.ts";
import { verifyDiscordRequest } from "./verify.ts";

export interface Env {
    DB: D1Database;
    DISCORD_PUBLIC_KEY?: string;
    DISCORD_BOT_TOKEN?: string;
    DISCORD_APP_ID?: string;
    GITHUB_TOKEN?: string;
    GITHUB_REPO: string;
}

/** 한 번의 cron 실행에서 보낼 최대 건수. 밀려 있어도 다음 분에 이어 보낸다. */
const MAX_SEND_PER_TICK = 25;

/** D1 을 `Db` 모양으로 감싼다. SQL 은 여기 한 곳에만 둔다. */
function d1(db: D1Database): Db {
    return {
        async insert(row) {
            const result = await db
                .prepare(
                    `INSERT INTO reservations (channel_id, user_id, content, due_at, created_at)
                     VALUES (?, ?, ?, ?, ?) RETURNING id`,
                )
                .bind(row.channel_id, row.user_id, row.content, row.due_at, row.created_at)
                .first<{ id: number }>();
            if (!result) throw new Error("예약을 저장하지 못했습니다");
            return result.id;
        },
        async listPending(userId) {
            const { results } = await db
                .prepare(
                    `SELECT id, channel_id, user_id, content, due_at, sent_at, canceled_at
                     FROM reservations
                     WHERE user_id = ? AND sent_at IS NULL AND canceled_at IS NULL
                     ORDER BY due_at`,
                )
                .bind(userId)
                .all<Reservation>();
            return results ?? [];
        },
        async cancel(id, userId, nowMs) {
            // user_id 조건을 같이 걸어 남의 예약은 애초에 맞지 않게 한다.
            const result = await db
                .prepare(
                    `UPDATE reservations SET canceled_at = ?
                     WHERE id = ? AND user_id = ? AND sent_at IS NULL AND canceled_at IS NULL`,
                )
                .bind(nowMs, id, userId)
                .run();
            return (result.meta.changes ?? 0) > 0 ? "canceled" : "not_found";
        },
    };
}

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        if (request.method === "GET") {
            // 사람이 주소를 열어 봤을 때. 살아 있다는 것만 알린다.
            return new Response("ktc4 discord bot", { status: 200 });
        }
        if (request.method !== "POST") {
            return new Response("method not allowed", { status: 405 });
        }

        // 서명은 **문자열 본문 그대로** 검증해야 한다. 파싱은 통과한 뒤에 한다.
        const rawBody = await request.text();
        const verified = await verifyDiscordRequest(
            rawBody,
            request.headers.get("x-signature-ed25519"),
            request.headers.get("x-signature-timestamp"),
            env.DISCORD_PUBLIC_KEY,
        );
        if (!verified) {
            return new Response("invalid request signature", { status: 401 });
        }

        const body = JSON.parse(rawBody);

        // Discord 가 엔드포인트가 살아 있는지 주기적으로 찔러 본다.
        if (body.type === InteractionType.PING) {
            return Response.json({ type: InteractionResponseType.PONG });
        }
        if (body.type !== InteractionType.APPLICATION_COMMAND) {
            return ephemeral("아직 지원하지 않는 상호작용입니다.");
        }

        const name: string = body.data?.name ?? "";
        const userId: string = body.member?.user?.id ?? body.user?.id ?? "";
        const channelId: string = body.channel_id ?? "";
        const options: Array<{ name: string; value: string | number }> = body.data?.options ?? [];
        const opt = (key: string) => options.find((o) => o.name === key)?.value;
        const now = Date.now();
        const db = d1(env.DB);

        switch (name) {
            case "예약":
                return ephemeral(
                    await handleReserve(
                        db,
                        {
                            userId,
                            channelId,
                            when: String(opt("시각") ?? ""),
                            content: String(opt("내용") ?? ""),
                        },
                        now,
                    ),
                );

            case "예약목록":
                return ephemeral(await handleList(db, userId));

            case "예약취소":
                return ephemeral(await handleCancel(db, userId, Number(opt("번호")), now));

            case "pr상태": {
                // GitHub 조회가 3초를 넘길 수 있다. 먼저 "생각 중"을 돌려주고 뒤이어 채운다.
                const appId = env.DISCORD_APP_ID;
                const token: string = body.token;
                if (!appId) return ephemeral("`DISCORD_APP_ID` 가 설정되지 않아 조회하지 못했습니다.");
                ctx.waitUntil(
                    handlePrStatus(env.GITHUB_REPO, env.GITHUB_TOKEN)
                        .then((text) => followUp(appId, token, text))
                        .catch((e) => followUp(appId, token, `조회 중 오류가 났습니다: ${e}`)),
                );
                return deferEphemeral();
            }

            default:
                return ephemeral(`모르는 명령어입니다: ${name}`);
        }
    },

    async scheduled(_event: ScheduledController, env: Env, _ctx: ExecutionContext): Promise<void> {
        const botToken = env.DISCORD_BOT_TOKEN;
        if (!botToken) {
            console.error("DISCORD_BOT_TOKEN 이 없어 예약을 보내지 못합니다");
            return;
        }

        const now = Date.now();
        const { results } = await env.DB
            .prepare(
                `SELECT id, channel_id, user_id, content, due_at, sent_at, canceled_at
                 FROM reservations
                 WHERE sent_at IS NULL AND canceled_at IS NULL AND due_at <= ?
                 ORDER BY due_at
                 LIMIT ?`,
            )
            .bind(now, MAX_SEND_PER_TICK)
            .all<Reservation>();

        for (const row of results ?? []) {
            const sent = await sendToChannel(botToken, row.channel_id, row.content);
            if (sent.ok) {
                await env.DB
                    .prepare(`UPDATE reservations SET sent_at = ?, last_error = NULL WHERE id = ?`)
                    .bind(Date.now(), row.id)
                    .run();
            } else {
                // 보내지 못한 건은 `sent_at` 을 비워 둔 채 이유만 남긴다 → 다음 분에 다시 시도한다.
                console.error(`예약 ${row.id} 발송 실패: ${sent.error}`);
                await env.DB
                    .prepare(`UPDATE reservations SET last_error = ? WHERE id = ?`)
                    .bind(sent.error, row.id)
                    .run();
            }
        }
    },
};

export { COMMANDS };
