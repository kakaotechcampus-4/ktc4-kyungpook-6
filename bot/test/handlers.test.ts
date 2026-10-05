import { beforeEach, describe, expect, it, vi } from "vitest";
import { handleCancel, handleList, handlePrStatus, handleReserve, type Db, type Reservation } from "../src/handlers.ts";

const NOW = Date.UTC(2026, 9, 1, 5, 30); // 2026-10-01 14:30 KST

/** 메모리에만 담는 가짜 DB. SQL 대신 배열로 같은 약속을 지킨다. */
function fakeDb(seed: Reservation[] = []): Db & { rows: Reservation[] } {
    const rows = [...seed];
    let nextId = Math.max(0, ...rows.map((r) => r.id)) + 1;
    return {
        rows,
        async insert(row) {
            const id = nextId++;
            rows.push({ ...row, id, sent_at: null, canceled_at: null });
            return id;
        },
        async listPending(userId) {
            return rows
                .filter((r) => r.user_id === userId && r.sent_at === null && r.canceled_at === null)
                .sort((a, b) => a.due_at - b.due_at);
        },
        async cancel(id, userId, nowMs) {
            const row = rows.find(
                (r) => r.id === id && r.user_id === userId && r.sent_at === null && r.canceled_at === null,
            );
            if (!row) return "not_found";
            row.canceled_at = nowMs;
            return "canceled";
        },
    };
}

const reservation = (over: Partial<Reservation> = {}): Reservation => ({
    id: 1,
    channel_id: "C1",
    user_id: "U1",
    content: "스탠드업",
    due_at: Date.UTC(2026, 9, 2, 0, 0),
    sent_at: null,
    canceled_at: null,
    ...over,
});

describe("/예약", () => {
    let db: ReturnType<typeof fakeDb>;
    beforeEach(() => {
        db = fakeDb();
    });

    it("저장하고 보낼 시각을 KST 로 알려준다", async () => {
        const msg = await handleReserve(
            db,
            { userId: "U1", channelId: "C1", when: "18:00", content: "배포합니다" },
            NOW,
        );
        expect(msg).toContain("2026-10-01 18:00");
        expect(msg).toContain("배포합니다");
        expect(db.rows).toHaveLength(1);
        expect(db.rows[0]).toMatchObject({ channel_id: "C1", user_id: "U1", content: "배포합니다" });
    });

    it("채널에 아무것도 남지 않는다는 것을 알려준다", async () => {
        const msg = await handleReserve(
            db,
            { userId: "U1", channelId: "C1", when: "18:00", content: "x" },
            NOW,
        );
        expect(msg).toContain("채널에는 아무것도 남지 않았습니다");
    });

    it("시각을 못 읽으면 저장하지 않는다", async () => {
        const msg = await handleReserve(
            db,
            { userId: "U1", channelId: "C1", when: "언젠가", content: "x" },
            NOW,
        );
        expect(msg).toContain("예약하지 못했습니다");
        expect(db.rows).toHaveLength(0);
    });

    it("내용이 비면 거부한다", async () => {
        const msg = await handleReserve(
            db,
            { userId: "U1", channelId: "C1", when: "18:00", content: "   " },
            NOW,
        );
        expect(msg).toContain("비어 있습니다");
        expect(db.rows).toHaveLength(0);
    });

    it("내용이 너무 길면 거부한다", async () => {
        const msg = await handleReserve(
            db,
            { userId: "U1", channelId: "C1", when: "18:00", content: "가".repeat(1501) },
            NOW,
        );
        expect(msg).toContain("넘습니다");
        expect(db.rows).toHaveLength(0);
    });

    it("한 사람이 20건을 넘기면 막는다", async () => {
        const many = Array.from({ length: 20 }, (_, i) => reservation({ id: i + 1 }));
        const full = fakeDb(many);
        const msg = await handleReserve(
            full,
            { userId: "U1", channelId: "C1", when: "18:00", content: "x" },
            NOW,
        );
        expect(msg).toContain("예약취소");
        expect(full.rows).toHaveLength(20);
    });
});

describe("/예약목록", () => {
    it("없으면 없다고 한다", async () => {
        expect(await handleList(fakeDb(), "U1")).toContain("건 예약이 없습니다");
    });

    it("내 것만 보여준다", async () => {
        const db = fakeDb([
            reservation({ id: 1, user_id: "U1", content: "내 것" }),
            reservation({ id: 2, user_id: "U2", content: "남의 것" }),
        ]);
        const msg = await handleList(db, "U1");
        expect(msg).toContain("내 것");
        expect(msg).not.toContain("남의 것");
    });

    it("보낸 것과 취소한 것은 빼고 보여준다", async () => {
        const db = fakeDb([
            reservation({ id: 1, content: "살아있음" }),
            reservation({ id: 2, content: "보냈음", sent_at: NOW }),
            reservation({ id: 3, content: "취소함", canceled_at: NOW }),
        ]);
        const msg = await handleList(db, "U1");
        expect(msg).toContain("살아있음");
        expect(msg).not.toContain("보냈음");
        expect(msg).not.toContain("취소함");
    });
});

describe("/예약취소", () => {
    it("내 예약은 취소된다", async () => {
        const db = fakeDb([reservation({ id: 7 })]);
        expect(await handleCancel(db, "U1", 7, NOW)).toContain("취소했습니다");
        expect(db.rows[0].canceled_at).toBe(NOW);
    });

    it("남의 예약은 취소되지 않고, 있다는 것도 알려주지 않는다", async () => {
        const db = fakeDb([reservation({ id: 7, user_id: "U2" })]);
        const msg = await handleCancel(db, "U1", 7, NOW);
        expect(msg).toContain("찾지 못했습니다");
        expect(db.rows[0].canceled_at).toBeNull();
    });

    it("없는 번호도 같은 문구로 답한다 — 번호를 넣어 보며 남의 예약을 알아낼 수 없게 한다", async () => {
        const mine = await handleCancel(fakeDb([reservation({ id: 7, user_id: "U2" })]), "U1", 7, NOW);
        const none = await handleCancel(fakeDb(), "U1", 7, NOW);
        expect(mine).toBe(none);
    });
});

describe("/pr상태", () => {
    const pr = (over: Record<string, unknown> = {}) => ({
        number: 58,
        title: "[8주차] 인증·권한 도입",
        draft: false,
        html_url: "https://github.com/o/r/pull/58",
        user: { login: "softkleenex" },
        base: { ref: "main" },
        created_at: new Date(Date.now() - 2 * 86_400_000).toISOString(),
        ...over,
    });

    it("열린 PR 을 요약한다", async () => {
        const fetchImpl = vi.fn(async () => Response.json([pr()]));
        const msg = await handlePrStatus("o/r", undefined, fetchImpl as unknown as typeof fetch);
        expect(msg).toContain("열린 PR 1건");
        expect(msg).toContain("#58");
        expect(msg).toContain("2일");
    });

    it("없으면 없다고 한다", async () => {
        const fetchImpl = vi.fn(async () => Response.json([]));
        expect(await handlePrStatus("o/r", undefined, fetchImpl as unknown as typeof fetch)).toContain(
            "열린 PR 이 없습니다",
        );
    });

    it("초안은 표시한다", async () => {
        const fetchImpl = vi.fn(async () => Response.json([pr({ draft: true })]));
        expect(await handlePrStatus("o/r", undefined, fetchImpl as unknown as typeof fetch)).toContain("초안");
    });

    it("토큰이 있으면 헤더에 싣는다", async () => {
        const fetchImpl = vi.fn(async () => Response.json([]));
        await handlePrStatus("o/r", "t0ken", fetchImpl as unknown as typeof fetch);
        const headers = (fetchImpl.mock.calls[0] as unknown as [string, { headers: Record<string, string> }])[1].headers;
        expect(headers.authorization).toBe("Bearer t0ken");
    });

    it("실패하면 상태코드를 알려준다", async () => {
        const fetchImpl = vi.fn(async () => new Response("nope", { status: 403 }));
        expect(await handlePrStatus("o/r", undefined, fetchImpl as unknown as typeof fetch)).toContain("403");
    });
});
