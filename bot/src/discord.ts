/**
 * Discord 와 주고받는 부분. 상호작용 응답 모양과 채널 발송만 담는다.
 *
 * 상호작용 응답은 **3초 안에** 돌려줘야 한다. 그보다 오래 걸리는 일(GitHub 조회)은
 * 먼저 "생각 중"(`DEFERRED`)을 보내고 나중에 후속 메시지로 채운다.
 */

/** 상호작용 종류. Discord 가 숫자로 보낸다. */
export const InteractionType = {
    PING: 1,
    APPLICATION_COMMAND: 2,
} as const;

/** 응답 종류. */
export const InteractionResponseType = {
    PONG: 1,
    CHANNEL_MESSAGE_WITH_SOURCE: 4,
    DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE: 5,
} as const;

/** 입력한 본인에게만 보이게 하는 플래그(EPHEMERAL). */
export const EPHEMERAL = 64;

/** 본인에게만 보이는 응답. 예약을 걸어도 채널에는 아무것도 남지 않는다. */
export function ephemeral(content: string): Response {
    return Response.json({
        type: InteractionResponseType.CHANNEL_MESSAGE_WITH_SOURCE,
        data: { content, flags: EPHEMERAL },
    });
}

/** "생각 중" 응답. 뒤이어 `followUp` 으로 내용을 채운다. */
export function deferEphemeral(): Response {
    return Response.json({
        type: InteractionResponseType.DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE,
        data: { flags: EPHEMERAL },
    });
}

/** 미뤄 둔 응답의 내용을 채운다. 상호작용 토큰은 15분간 쓸 수 있다. */
export async function followUp(appId: string, token: string, content: string): Promise<void> {
    await fetch(`https://discord.com/api/v10/webhooks/${appId}/${token}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ content, flags: EPHEMERAL }),
    });
}

/**
 * 채널에 메시지를 보낸다 — 예약 시각이 됐을 때 봇이 대신 보내는 그 메시지다.
 *
 * 실패하면 이유를 문자열로 돌려준다. 호출하는 쪽이 그 이유를 저장해 두고 다음 분에 다시 시도한다.
 */
export async function sendToChannel(
    botToken: string,
    channelId: string,
    content: string,
): Promise<{ ok: true } | { ok: false; error: string }> {
    const res = await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
        method: "POST",
        headers: {
            authorization: `Bot ${botToken}`,
            "content-type": "application/json",
        },
        body: JSON.stringify({
            content,
            // 예약 메시지가 @everyone 을 울리지 않게 한다. 본문에 적혀 있어도 멘션으로 치지 않는다.
            allowed_mentions: { parse: ["users", "roles"] },
        }),
    });
    if (res.ok) return { ok: true };
    return { ok: false, error: `${res.status} ${(await res.text()).slice(0, 200)}` };
}
