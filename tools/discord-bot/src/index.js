/**
 * 경북대 6팀 디스코드 봇 — 슬래시 명령어와 예약 메시지.
 *
 * 왜 Workers 인가 — 예약 발송은 그 시각에 뭔가 깨어 있어야 한다. 상시 켜둘 PC 를 두는
 * 대신, 슬래시 명령어는 디스코드의 HTTP Interactions 엔드포인트로 받고(게이트웨이 상시
 * 연결이 필요 없다) 예약 발송은 Cron Trigger 가 1분마다 D1 을 확인해서 보낸다.
 *
 * 입력은 전부 ephemeral(flags 64) 로 답한다 — **입력한 본인만 보인다.** 새벽에 예약을
 * 걸어도 채널에는 아무것도 남지 않고, 지정한 시각에 봇이 대신 발송한다.
 */

import { verifyRequest } from "./verify.js";
import { formatKst, parseSchedule } from "./time.js";

const EPHEMERAL = 64;
const DISCORD_API = "https://discord.com/api/v10";

// 디스코드 인터랙션 타입
const PING = 1;
const APPLICATION_COMMAND = 2;
const PONG = 1;
const CHANNEL_MESSAGE_WITH_SOURCE = 4;

/** 한 번의 cron 실행에서 최대 몇 건까지 보낼지. 밀린 예약이 많아도 실행 시간을 넘기지 않는다. */
const SEND_BATCH = 20;
/** 발송 실패를 몇 번까지 다시 시도할지 */
const MAX_ATTEMPTS = 3;

function ephemeral(content) {
  return json({ type: CHANNEL_MESSAGE_WITH_SOURCE, data: { content, flags: EPHEMERAL } });
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** 슬래시 명령의 옵션을 이름으로 꺼낸다 */
function option(interaction, name) {
  const found = (interaction.data?.options ?? []).find((o) => o.name === name);
  return found?.value;
}

function userId(interaction) {
  return interaction.member?.user?.id ?? interaction.user?.id ?? null;
}

// ───────────────────────────── 명령 처리 ─────────────────────────────

async function cmdSchedule(interaction, env, nowMs) {
  const when = option(interaction, "시각");
  const content = String(option(interaction, "내용") ?? "").trim();
  const channelId = interaction.channel_id;

  if (!content) return ephemeral("보낼 내용이 비어 있습니다.");
  if (content.length > 1800) return ephemeral("내용이 너무 깁니다 (1800자까지).");

  const parsed = parseSchedule(when, nowMs);
  if (!parsed.ok) return ephemeral(`⚠️ ${parsed.reason}`);

  const result = await env.DB.prepare(
    `INSERT INTO scheduled_messages (channel_id, user_id, content, due_at, created_at)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(channelId, userId(interaction), content, parsed.dueAt, Math.floor(nowMs / 1000))
    .run();

  const id = result.meta?.last_row_id;
  return ephemeral(
    `✅ 예약했습니다 (#${id})\n` +
      `**${formatKst(parsed.dueAt)}** 에 이 채널로 보냅니다.\n` +
      `이 확인 메시지는 저만 보이고, 예약한 사실도 지금은 아무에게도 보이지 않습니다.\n\n` +
      `> ${content.slice(0, 200)}${content.length > 200 ? "…" : ""}`,
  );
}

async function cmdList(interaction, env) {
  const { results } = await env.DB.prepare(
    `SELECT id, due_at, content FROM scheduled_messages
     WHERE user_id = ? AND sent_at IS NULL ORDER BY due_at ASC LIMIT 20`,
  )
    .bind(userId(interaction))
    .all();

  if (!results?.length) return ephemeral("예약된 메시지가 없습니다.");

  const lines = results.map(
    (r) => `**#${r.id}** ${formatKst(r.due_at)}\n> ${String(r.content).slice(0, 80)}`,
  );
  return ephemeral(`예약 ${results.length}건\n\n${lines.join("\n\n")}`);
}

async function cmdCancel(interaction, env) {
  const id = Number(option(interaction, "번호"));
  if (!Number.isInteger(id)) return ephemeral("취소할 예약 번호를 숫자로 적어주세요.");

  // 남의 예약은 못 지운다 — user_id 를 조건에 같이 넣는다.
  const res = await env.DB.prepare(
    `DELETE FROM scheduled_messages WHERE id = ? AND user_id = ? AND sent_at IS NULL`,
  )
    .bind(id, userId(interaction))
    .run();

  return ephemeral(
    res.meta?.changes
      ? `🗑️ #${id} 예약을 취소했습니다.`
      : `#${id} 예약을 찾지 못했습니다. 이미 보냈거나, 본인이 건 예약이 아닙니다.`,
  );
}

async function cmdPrStatus(env) {
  if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) {
    return ephemeral("GITHUB_TOKEN 과 GITHUB_REPO 가 설정되지 않아 조회할 수 없습니다.");
  }
  const res = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}/pulls?state=open`, {
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "ktc4-kyungpook-6-bot",
    },
  });
  if (!res.ok) return ephemeral(`GitHub 조회 실패 (${res.status})`);

  const pulls = await res.json();
  if (!pulls.length) return ephemeral("열린 PR 이 없습니다.");

  const lines = pulls.map((p) => {
    const draft = p.draft ? " *(초안)*" : "";
    return `• [#${p.number}](${p.html_url}) → \`${p.base.ref}\`${draft}\n  ${p.title} — ${p.user.login}`;
  });
  return ephemeral(`열린 PR ${pulls.length}건\n\n${lines.join("\n")}`);
}

// ───────────────────────────── 엔트리포인트 ─────────────────────────────

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      // 헬스체크용. 여기에 아무 정보도 담지 않는다.
      return new Response("ok", { status: 200 });
    }

    const raw = await request.text();
    const valid = await verifyRequest(
      raw,
      request.headers.get("X-Signature-Ed25519"),
      request.headers.get("X-Signature-Timestamp"),
      env.DISCORD_PUBLIC_KEY,
    );
    // 디스코드는 등록 시 일부러 틀린 서명으로도 찔러본다. 401 로 답해야 검증이 통과한다.
    if (!valid) return new Response("invalid request signature", { status: 401 });

    const interaction = JSON.parse(raw);
    if (interaction.type === PING) return json({ type: PONG });
    if (interaction.type !== APPLICATION_COMMAND) return json({ type: PONG });

    const now = Date.now();
    try {
      switch (interaction.data?.name) {
        case "예약":
          return await cmdSchedule(interaction, env, now);
        case "예약목록":
          return await cmdList(interaction, env);
        case "예약취소":
          return await cmdCancel(interaction, env);
        case "pr상태":
          return await cmdPrStatus(env);
        default:
          return ephemeral("모르는 명령입니다.");
      }
    } catch (e) {
      // 사용자에게는 짧게, 로그에는 원문을 남긴다.
      console.error("command failed", interaction.data?.name, e);
      return ephemeral("처리 중 오류가 났습니다. 잠시 뒤 다시 시도해 주세요.");
    }
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(flushDue(env, Math.floor(Date.now() / 1000)));
  },
};

/**
 * 시각이 된 예약을 보낸다.
 *
 * **같은 메시지를 두 번 보내지 않는 방법** — 먼저 `sent_at` 을 찍어 그 행을 잡고(claim),
 * 그 UPDATE 가 실제로 한 행을 바꿨을 때만 발송한다. 발송이 실패하면 `sent_at` 을 다시
 * 비우고 시도 횟수를 올린다. 순서를 반대로 하면(보내고 나서 표시) 표시가 실패할 때
 * 다음 분에 또 보낸다 — 중복이 한 번 나가면 되돌릴 수 없으니 이쪽을 택했다.
 */
export async function flushDue(env, nowSec) {
  const { results } = await env.DB.prepare(
    `SELECT id, channel_id, content FROM scheduled_messages
     WHERE sent_at IS NULL AND due_at <= ? AND attempts < ?
     ORDER BY due_at ASC LIMIT ?`,
  )
    .bind(nowSec, MAX_ATTEMPTS, SEND_BATCH)
    .all();

  for (const row of results ?? []) {
    const claim = await env.DB.prepare(
      `UPDATE scheduled_messages SET sent_at = ? WHERE id = ? AND sent_at IS NULL`,
    )
      .bind(nowSec, row.id)
      .run();
    if (!claim.meta?.changes) continue; // 다른 실행이 이미 가져갔다

    const ok = await sendToChannel(env, row.channel_id, row.content);
    if (!ok) {
      await env.DB.prepare(
        `UPDATE scheduled_messages SET sent_at = NULL, attempts = attempts + 1 WHERE id = ?`,
      )
        .bind(row.id)
        .run();
    }
  }
}

async function sendToChannel(env, channelId, content) {
  const res = await fetch(`${DISCORD_API}/channels/${channelId}/messages`, {
    method: "POST",
    headers: {
      Authorization: `Bot ${env.DISCORD_BOT_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ content }),
  });
  if (!res.ok) console.error("discord send failed", res.status, await res.text());
  return res.ok;
}
