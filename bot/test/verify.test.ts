import { describe, expect, it } from "vitest";
import { verifyDiscordRequest } from "../src/verify.ts";

const enc = new TextEncoder();
const toHex = (b: ArrayBuffer | Uint8Array) =>
    [...new Uint8Array(b)].map((n) => n.toString(16).padStart(2, "0")).join("");

/** Discord 가 하는 것과 같은 방식으로 서명한다 — `타임스탬프 + 본문` 을 Ed25519 로. */
async function signed(body: string, timestamp: string) {
    const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
        "sign",
        "verify",
    ])) as CryptoKeyPair;
    const signature = await crypto.subtle.sign(
        { name: "Ed25519" },
        pair.privateKey,
        enc.encode(timestamp + body),
    );
    const publicKey = await crypto.subtle.exportKey("raw", pair.publicKey);
    return { signature: toHex(signature), publicKey: toHex(publicKey) };
}

describe("서명 검증", () => {
    const body = JSON.stringify({ type: 1 });
    const ts = "1759300000";

    it("제대로 서명된 요청은 통과시킨다", async () => {
        const { signature, publicKey } = await signed(body, ts);
        expect(await verifyDiscordRequest(body, signature, ts, publicKey)).toBe(true);
    });

    it("본문이 한 글자라도 바뀌면 거부한다", async () => {
        const { signature, publicKey } = await signed(body, ts);
        expect(await verifyDiscordRequest(body + " ", signature, ts, publicKey)).toBe(false);
    });

    it("타임스탬프가 바뀌면 거부한다 — 서명 대상에 들어가기 때문이다", async () => {
        const { signature, publicKey } = await signed(body, ts);
        expect(await verifyDiscordRequest(body, signature, "1759300001", publicKey)).toBe(false);
    });

    it("다른 키로 서명한 요청은 거부한다", async () => {
        const mine = await signed(body, ts);
        const other = await signed(body, ts);
        expect(await verifyDiscordRequest(body, other.signature, ts, mine.publicKey)).toBe(false);
    });

    it("헤더나 공개키가 없으면 거부한다", async () => {
        const { signature, publicKey } = await signed(body, ts);
        expect(await verifyDiscordRequest(body, null, ts, publicKey)).toBe(false);
        expect(await verifyDiscordRequest(body, signature, null, publicKey)).toBe(false);
        expect(await verifyDiscordRequest(body, signature, ts, undefined)).toBe(false);
    });

    it.each([
        ["16진수가 아닌 서명", "zzzz", 64],
        ["길이가 홀수인 서명", "abc", 64],
        ["길이가 안 맞는 서명", "ab".repeat(10), 64],
    ])("%s 는 거부한다", async (_label, signature) => {
        const { publicKey } = await signed(body, ts);
        expect(await verifyDiscordRequest(body, signature, ts, publicKey)).toBe(false);
    });

    it("공개키 길이가 32바이트가 아니면 거부한다", async () => {
        const { signature } = await signed(body, ts);
        expect(await verifyDiscordRequest(body, signature, ts, "ab".repeat(16))).toBe(false);
    });
});
