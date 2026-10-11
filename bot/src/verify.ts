/**
 * Discord 요청 서명 검증.
 *
 * Discord 는 우리 엔드포인트로 상호작용을 보낼 때 본문을 앱의 개인키로 서명하고,
 * `X-Signature-Ed25519`(서명) 와 `X-Signature-Timestamp`(타임스탬프) 헤더에 담아 준다.
 * 서명 대상은 `타임스탬프 + 본문` 을 이어 붙인 바이트다.
 *
 * **검증을 통과하지 못한 요청은 401 로 거부한다.** 엔드포인트는 인터넷에 열려 있으므로
 * 이 검증이 유일한 신원 확인이다. Discord 는 등록 과정에서 일부러 잘못된 서명을 보내
 * 401 이 나오는지 확인하고, 통과하면 엔드포인트를 받아 준다.
 */

/**
 * 16진 문자열을 바이트로. 길이가 홀수이거나 16진수가 아니면 `null`.
 *
 * `ArrayBuffer` 를 명시해 만든다 — `new Uint8Array(n)` 는 `ArrayBufferLike` 로 추론돼
 * `crypto.subtle` 이 받는 `BufferSource` 에 들어가지 않는다(SharedArrayBuffer 일 수 있어서다).
 */
function hexToBytes(hex: string): Uint8Array<ArrayBuffer> | null {
    if (hex.length === 0 || hex.length % 2 !== 0) return null;
    const out = new Uint8Array(new ArrayBuffer(hex.length / 2));
    for (let i = 0; i < out.length; i += 1) {
        const byte = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
        if (Number.isNaN(byte)) return null;
        out[i] = byte;
    }
    return out;
}

/**
 * 서명이 맞는지 본다.
 *
 * @param rawBody 본문 **문자열 그대로**. `JSON.parse` 후 다시 직렬화한 값으로는 안 된다 —
 *                키 순서나 공백이 달라지면 서명이 깨진다.
 * @param publicKeyHex Discord Developer Portal 의 앱 공개키(16진).
 */
export async function verifyDiscordRequest(
    rawBody: string,
    signatureHex: string | null,
    timestamp: string | null,
    publicKeyHex: string | undefined,
): Promise<boolean> {
    if (!signatureHex || !timestamp || !publicKeyHex) return false;

    const signature = hexToBytes(signatureHex);
    const publicKey = hexToBytes(publicKeyHex);
    // Ed25519 서명은 64바이트, 공개키는 32바이트로 길이가 고정이다.
    if (!signature || signature.length !== 64) return false;
    if (!publicKey || publicKey.length !== 32) return false;

    try {
        const key = await crypto.subtle.importKey(
            "raw",
            publicKey,
            { name: "Ed25519" },
            false,
            ["verify"],
        );
        return await crypto.subtle.verify(
            { name: "Ed25519" },
            key,
            signature,
            new TextEncoder().encode(timestamp + rawBody),
        );
    } catch {
        // 공개키가 Ed25519 키가 아닌 경우 등. 통과시키지 않는다.
        return false;
    }
}
