import assert from "node:assert/strict";
import { test } from "node:test";

import { flushDue } from "../src/index.js";

/**
 * D1 의 최소 흉내. flushDue 가 쓰는 세 가지 질의만 알아듣는다.
 *   SELECT ... WHERE sent_at IS NULL AND due_at <= ?
 *   UPDATE ... SET sent_at = ? WHERE id = ? AND sent_at IS NULL     (claim)
 *   UPDATE ... SET sent_at = NULL, attempts = attempts + 1          (실패 되돌리기)
 */
function fakeDb(rows) {
  return {
    rows,
    prepare(sql) {
      const db = this;
      return {
        args: [],
        bind(...args) {
          this.args = args;
          return this;
        },
        async all() {
          const [nowSec, maxAttempts, limit] = this.args;
          const due = db.rows
            .filter((r) => r.sent_at == null && r.due_at <= nowSec && r.attempts < maxAttempts)
            .sort((a, b) => a.due_at - b.due_at)
            .slice(0, limit);
          return { results: due.map((r) => ({ ...r })) };
        },
        async run() {
          if (sql.includes("sent_at = NULL")) {
            const [id] = this.args;
            const row = db.rows.find((r) => r.id === id);
            row.sent_at = null;
            row.attempts += 1;
            return { meta: { changes: 1 } };
          }
          // claim
          const [nowSec, id] = this.args;
          const row = db.rows.find((r) => r.id === id && r.sent_at == null);
          if (!row) return { meta: { changes: 0 } };
          row.sent_at = nowSec;
          return { meta: { changes: 1 } };
        },
      };
    },
  };
}

function row(over = {}) {
  return { id: 1, channel_id: "C1", content: "안녕", due_at: 100, attempts: 0, sent_at: null, ...over };
}

function stubFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) });
    return handler(calls.length);
  };
  return calls;
}

const OK = () => ({ ok: true, status: 200, text: async () => "" });
const FAIL = () => ({ ok: false, status: 500, text: async () => "boom" });

test("시각이 된 예약만 보낸다", async () => {
  const db = fakeDb([row({ id: 1, due_at: 100 }), row({ id: 2, due_at: 999, content: "나중" })]);
  const calls = stubFetch(OK);

  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 200);

  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.content, "안녕");
  assert.equal(db.rows[0].sent_at, 200);
  assert.equal(db.rows[1].sent_at, null); // 아직 시각이 안 됐다
});

test("보낸 예약은 다음 실행에서 다시 보내지 않는다", async () => {
  const db = fakeDb([row({ id: 1, due_at: 100 })]);
  const calls = stubFetch(OK);

  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 200);
  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 260);

  assert.equal(calls.length, 1, "1분 뒤 cron 이 또 돌아도 한 번만 나가야 한다");
});

test("발송이 실패하면 되돌려서 다음에 다시 시도한다", async () => {
  const db = fakeDb([row({ id: 1, due_at: 100 })]);
  stubFetch(FAIL);

  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 200);

  assert.equal(db.rows[0].sent_at, null, "실패했으면 보낸 것으로 두면 안 된다");
  assert.equal(db.rows[0].attempts, 1);
});

test("3번 실패하면 더 시도하지 않는다 — 무한 재시도로 채널을 때리지 않는다", async () => {
  const db = fakeDb([row({ id: 1, due_at: 100 })]);
  const calls = stubFetch(FAIL);

  for (let i = 0; i < 5; i += 1) await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 200 + i);

  assert.equal(calls.length, 3);
  assert.equal(db.rows[0].attempts, 3);
});

test("한 건이 실패해도 나머지는 보낸다", async () => {
  const db = fakeDb([
    row({ id: 1, due_at: 100, content: "실패할 것" }),
    row({ id: 2, due_at: 101, content: "성공할 것" }),
  ]);
  const calls = stubFetch((n) => (n === 1 ? FAIL() : OK()));

  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "t" }, 200);

  assert.equal(calls.length, 2);
  assert.equal(db.rows[0].sent_at, null);
  assert.equal(db.rows[1].sent_at, 200);
});

test("봇 토큰으로 해당 채널에 보낸다", async () => {
  const db = fakeDb([row({ id: 1, channel_id: "C-999" })]);
  const calls = stubFetch(OK);

  await flushDue({ DB: db, DISCORD_BOT_TOKEN: "tok" }, 200);

  assert.match(calls[0].url, /\/channels\/C-999\/messages$/);
});
