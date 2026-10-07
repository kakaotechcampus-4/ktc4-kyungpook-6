/**
 * 명령어별 처리. Discord·D1 바인딩을 직접 받지 않고 필요한 것만 인자로 받는다 —
 * 테스트에서 가짜 DB 를 넣을 수 있게 하려고 이렇게 나눴다.
 */

import { fetchOpenPrs, line, type PrInfo } from "./github.ts";
import { human, label, nextMilestone } from "./schedule.ts";
import { formatKst, parseDueAt } from "./time.ts";

/** 예약 한 건. DB 컬럼과 같은 모양이다. */
export interface Reservation {
    id: number;
    channel_id: string;
    user_id: string;
    content: string;
    due_at: number;
    sent_at: number | null;
    canceled_at: number | null;
}

/** 이 모듈이 DB 에 요구하는 것. D1 의 일부만 쓴다. */
export interface Db {
    insert(row: Omit<Reservation, "id" | "sent_at" | "canceled_at"> & { created_at: number }): Promise<number>;
    listPending(userId: string): Promise<Reservation[]>;
    cancel(id: number, userId: string, nowMs: number): Promise<"canceled" | "not_found">;
}

/** 한 번에 담을 수 있는 내용 길이. Discord 메시지 상한(2000)보다 넉넉히 아래로 둔다. */
const MAX_CONTENT = 1500;
/** 한 사람이 동시에 들고 있을 수 있는 예약 수. 실수로 쌓는 것을 막는 선이다. */
const MAX_PENDING_PER_USER = 20;

export async function handleReserve(
    db: Db,
    args: { userId: string; channelId: string; when: string; content: string },
    nowMs: number,
): Promise<string> {
    const content = args.content.trim();
    if (content === "") return "보낼 내용이 비어 있습니다.";
    if (content.length > MAX_CONTENT) {
        return `내용이 ${MAX_CONTENT}자를 넘습니다 (${content.length}자).`;
    }

    const parsed = parseDueAt(args.when, nowMs);
    if (!parsed.ok) return `예약하지 못했습니다 — ${parsed.reason}`;

    const pending = await db.listPending(args.userId);
    if (pending.length >= MAX_PENDING_PER_USER) {
        return `예약이 이미 ${pending.length}건입니다. \`/예약취소\` 로 정리한 뒤 다시 걸어 주세요.`;
    }

    const id = await db.insert({
        channel_id: args.channelId,
        user_id: args.userId,
        content,
        due_at: parsed.dueAt,
        created_at: nowMs,
    });

    return [
        `**${formatKst(parsed.dueAt)}** 에 이 채널로 보냅니다. (번호 ${id})`,
        "",
        "> " + content.split("\n").join("\n> "),
        "",
        "지금은 이 안내만 본인에게 보이고, 채널에는 아무것도 남지 않았습니다.",
    ].join("\n");
}

export async function handleList(db: Db, userId: string): Promise<string> {
    const rows = await db.listPending(userId);
    if (rows.length === 0) return "건 예약이 없습니다.";

    const lines = rows.map((r) => {
        const preview = r.content.length > 60 ? `${r.content.slice(0, 60)}…` : r.content;
        return `\`${r.id}\`  ${formatKst(r.due_at)}  ${preview.replace(/\n/g, " ")}`;
    });
    return [`예약 ${rows.length}건입니다. 취소는 \`/예약취소 번호\`.`, "", ...lines].join("\n");
}

export async function handleCancel(db: Db, userId: string, id: number, nowMs: number): Promise<string> {
    const result = await db.cancel(id, userId, nowMs);
    if (result === "canceled") return `번호 ${id} 예약을 취소했습니다.`;
    // 남의 예약을 지우려 한 경우와 없는 번호를 구분해 주지 않는다 — 번호를 넣어 보며
    // 다른 사람의 예약이 있는지 알아낼 수 있게 되기 때문이다.
    return `번호 ${id} 예약을 찾지 못했습니다. \`/예약목록\` 으로 번호를 확인해 주세요.`;
}

/** 열린 PR 요약. GitHub 조회가 3초를 넘길 수 있어 호출하는 쪽에서 미뤄 둔 응답으로 쓴다. */
export async function handlePrStatus(
    repo: string,
    token: string | undefined,
    fetchImpl: typeof fetch = fetch,
    nowMs: number = Date.now(),
): Promise<string> {
    let prs: PrInfo[];
    try {
        prs = await fetchOpenPrs(repo, token, fetchImpl);
    } catch (e) {
        return `${e}. 토큰이 없거나 만료됐을 수 있습니다.`;
    }
    if (prs.length === 0) return "열린 PR 이 없습니다. 🎉";

    const stuck = prs.filter((p) => !p.draft && p.reviewers.length === 0).length;
    const head = stuck
        ? `열린 PR ${prs.length}건 · 그중 ${stuck}건은 리뷰어가 없습니다.`
        : `열린 PR ${prs.length}건입니다.`;
    return [head, "", ...prs.map((p) => line(p, nowMs))].join("\n");
}

/**
 * 나와 관련된 PR 만. "내가 지금 뭘 해야 하지" 에 바로 답한다.
 *
 * 디스코드 사용자 ID 를 깃허브 로그인으로 바꿔야 해서 매핑이 필요하다. 매핑이 없으면
 * 그 사실을 알려 주고 끝낸다 — 빈 목록을 보여주면 "할 일이 없다"로 오해한다.
 */
export async function handleMyTurn(
    repo: string,
    token: string | undefined,
    membersJson: string | undefined,
    discordUserId: string,
    fetchImpl: typeof fetch = fetch,
    nowMs: number = Date.now(),
): Promise<string> {
    const login = githubLoginOf(membersJson, discordUserId);
    if (!login) {
        return "디스코드 계정과 깃허브 계정이 연결돼 있지 않습니다.\n"
            + "`DISCORD_MEMBERS` 시크릿에 추가해야 합니다 — PM 에게 말씀해 주세요.";
    }

    let prs: PrInfo[];
    try {
        prs = await fetchOpenPrs(repo, token, fetchImpl);
    } catch (e) {
        return `${e}`;
    }

    const toReview = prs.filter((p) => !p.draft && p.reviewers.includes(login));
    const mine = prs.filter((p) => p.author === login);
    if (toReview.length === 0 && mine.length === 0) {
        return `\`${login}\` 님이 볼 PR 도, 올린 PR 도 없습니다. 🎉`;
    }

    const out: string[] = [];
    if (toReview.length) {
        out.push(`**리뷰해 주셔야 할 PR ${toReview.length}건**`, "");
        out.push(...toReview.map((p) => line(p, nowMs)), "");
    }
    if (mine.length) {
        out.push(`**올리신 PR ${mine.length}건**`, "");
        out.push(...mine.map((p) => line(p, nowMs)));
    }
    return out.join("\n").trim();
}

/** 다음 마감까지 남은 시간과, 지금 준비가 됐는지. */
export async function handleDeadline(
    repo: string,
    token: string | undefined,
    fetchImpl: typeof fetch = fetch,
    nowMs: number = Date.now(),
): Promise<string> {
    const { m, leftMs } = nextMilestone(nowMs);
    const out = [
        `다음 마감은 **${label(m)} · ${m.what}** (${m.who}) 입니다.`,
        `남은 시간 **${human(leftMs)}**`,
        "",
    ];

    let prs: PrInfo[];
    try {
        prs = await fetchOpenPrs(repo, token, fetchImpl);
    } catch (e) {
        out.push(`${e}`);
        return out.join("\n");
    }

    const mainPr = prs.find((p) => p.base === "main" && !p.draft);
    out.push(mainPr ? `✅ 멘토 PR: #${mainPr.number}` : "❌ 멘토 PR 이 아직 없습니다");

    const dev = prs.filter((p) => p.base === "develop" && !p.draft);
    const noReviewer = dev.filter((p) => p.reviewers.length === 0);
    const waiting = dev.filter((p) => p.reviewers.length > 0 && p.reviewCount === 0);
    if (dev.length === 0) {
        out.push("✅ 열린 develop PR 없음");
    } else {
        out.push(
            `${noReviewer.length || waiting.length ? "⚠️" : "✅"} 열린 develop PR ${dev.length}건`
            + (noReviewer.length ? ` · 리뷰어 없음 ${noReviewer.length}건` : "")
            + (waiting.length ? ` · 리뷰 대기 ${waiting.length}건` : ""),
        );
        for (const p of dev.slice(0, 5)) out.push(`　#${p.number} ${p.title}`);
    }
    return out.join("\n");
}

/** 매핑 JSON 에서 디스코드 ID → 깃허브 로그인. 형식은 .github/discord-members.example.json */
function githubLoginOf(membersJson: string | undefined, discordUserId: string): string | null {
    if (!membersJson || !discordUserId) return null;
    try {
        const t = JSON.parse(membersJson) as { members?: Record<string, { discord?: string }> };
        for (const [login, v] of Object.entries(t.members ?? {})) {
            if (v?.discord === discordUserId) return login;
        }
    } catch {
        return null;   // 매핑이 깨져도 명령어 자체는 죽지 않는다
    }
    return null;
}
