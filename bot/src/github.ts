/**
 * 열린 PR 을 한 번에 읽어 온다. `/pr상태` · `/마감` · `/내차례` 가 같이 쓴다.
 *
 * 리뷰 상태는 PR 마다 따로 조회해야 해서 호출이 PR 수만큼 늘어난다. 익명 호출은
 * 시간당 60회라 금방 막히므로 `GITHUB_TOKEN` 을 넣어 두는 것을 전제로 한다
 * (토큰이 있으면 5000회). 토큰이 없어도 동작은 하되 PR 이 몇 개만 돼도 위험하다.
 */

export interface PrInfo {
    number: number;
    title: string;
    url: string;
    author: string;
    base: string;
    draft: boolean;
    createdAt: number;
    reviewers: string[];
    /** 사람별 마지막 리뷰만 본다. COMMENTED 는 승인·변경요청을 덮지 않는다. */
    state: "APPROVED" | "CHANGES_REQUESTED" | null;
    reviewCount: number;
}

interface RawPr {
    number: number;
    title: string;
    html_url: string;
    draft: boolean;
    created_at: string;
    user: { login: string };
    base: { ref: string };
    requested_reviewers?: Array<{ login: string; type?: string }>;
}

interface RawReview {
    state: string;
    user: { login: string } | null;
}

function headers(token?: string): Record<string, string> {
    const h: Record<string, string> = {
        accept: "application/vnd.github+json",
        "user-agent": "ktc4-discord-bot",
    };
    if (token) h.authorization = `Bearer ${token}`;
    return h;
}

function latestState(reviews: RawReview[]): PrInfo["state"] {
    const byUser = new Map<string, string>();
    for (const r of reviews) {
        if (r.state === "COMMENTED") continue;
        if (r.user?.login) byUser.set(r.user.login, r.state);
    }
    const states = new Set(byUser.values());
    if (states.has("CHANGES_REQUESTED")) return "CHANGES_REQUESTED";
    if (states.has("APPROVED")) return "APPROVED";
    return null;
}

export async function fetchOpenPrs(
    repo: string,
    token: string | undefined,
    fetchImpl: typeof fetch = fetch,
): Promise<PrInfo[]> {
    const res = await fetchImpl(
        `https://api.github.com/repos/${repo}/pulls?state=open&sort=created&direction=asc&per_page=20`,
        { headers: headers(token) },
    );
    if (!res.ok) throw new Error(`GitHub 조회 실패 (${res.status})`);
    const raw = (await res.json()) as RawPr[];

    // 리뷰는 PR 마다 따로 받아야 한다. 동시에 던진다.
    const reviews = await Promise.all(
        raw.map(async (pr) => {
            const r = await fetchImpl(
                `https://api.github.com/repos/${repo}/pulls/${pr.number}/reviews?per_page=100`,
                { headers: headers(token) },
            );
            return r.ok ? ((await r.json()) as RawReview[]) : [];
        }),
    );

    return raw.map((pr, i) => ({
        number: pr.number,
        title: pr.title,
        url: pr.html_url,
        author: pr.user.login,
        base: pr.base.ref,
        draft: pr.draft,
        createdAt: Date.parse(pr.created_at),
        reviewers: (pr.requested_reviewers ?? []).filter((u) => u.type !== "Bot").map((u) => u.login),
        state: latestState(reviews[i]),
        reviewCount: reviews[i].length,
    }));
}

/** `3시간` · `2일` 처럼 사람이 읽는 경과 시간. */
export function ago(fromMs: number, nowMs: number): string {
    const h = Math.floor((nowMs - fromMs) / 3_600_000);
    if (h < 1) return "방금";
    if (h < 24) return `${h}시간`;
    return `${Math.floor(h / 24)}일`;
}

/** PR 한 건을 한 줄로. 리뷰 상태를 앞에 세워 "봐야 하는지"가 먼저 보이게 한다. */
export function line(pr: PrInfo, nowMs: number): string {
    const mark = pr.draft
        ? "📝"
        : pr.state === "APPROVED"
          ? "🟢"
          : pr.state === "CHANGES_REQUESTED"
            ? "🔴"
            : pr.reviewers.length === 0
              ? "⚠️"
              : "🕐";
    const who = pr.reviewers.length ? `리뷰어 ${pr.reviewers.join(", ")}` : "리뷰어 없음";
    const status = pr.draft
        ? "초안"
        : pr.state === "APPROVED"
          ? "승인됨"
          : pr.state === "CHANGES_REQUESTED"
            ? "변경 요청"
            : pr.reviewCount > 0
              ? "리뷰 중"
              : "리뷰 대기";
    return `${mark} [#${pr.number}](${pr.url}) → \`${pr.base}\` · ${pr.author} · ${ago(pr.createdAt, nowMs)}\n`
        + `　${pr.title}\n`
        + `　${who} · ${status}`;
}
