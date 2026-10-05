/**
 * 명령어별 처리. Discord·D1 바인딩을 직접 받지 않고 필요한 것만 인자로 받는다 —
 * 테스트에서 가짜 DB 를 넣을 수 있게 하려고 이렇게 나눴다.
 */

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
): Promise<string> {
    const headers: Record<string, string> = {
        accept: "application/vnd.github+json",
        "user-agent": "ktc4-discord-bot",
    };
    if (token) headers.authorization = `Bearer ${token}`;

    const res = await fetchImpl(
        `https://api.github.com/repos/${repo}/pulls?state=open&sort=created&direction=asc&per_page=20`,
        { headers },
    );
    if (!res.ok) {
        return `GitHub 조회에 실패했습니다 (${res.status}). 토큰이 없거나 만료됐을 수 있습니다.`;
    }

    const prs = (await res.json()) as Array<{
        number: number;
        title: string;
        draft: boolean;
        html_url: string;
        user: { login: string };
        base: { ref: string };
        created_at: string;
    }>;
    if (prs.length === 0) return "열린 PR 이 없습니다.";

    const lines = prs.map((pr) => {
        const days = Math.floor((Date.now() - Date.parse(pr.created_at)) / (24 * 60 * 60 * 1000));
        const age = days === 0 ? "오늘" : `${days}일`;
        const draft = pr.draft ? " *(초안)*" : "";
        return `- [#${pr.number}](${pr.html_url}) → \`${pr.base.ref}\` · ${pr.user.login} · ${age}${draft}\n  ${pr.title}`;
    });
    return [`열린 PR ${prs.length}건입니다.`, "", ...lines].join("\n");
}
