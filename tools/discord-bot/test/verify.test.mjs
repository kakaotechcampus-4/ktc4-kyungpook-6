import assert from "node:assert/strict";
import { test } from "node:test";
import { webcrypto } from "node:crypto";

import { verifyRequest } from "../src/verify.js";

// Workers 에는 crypto 가 전역으로 있다. node 에서 테스트할 때만 채워 넣는다.
if (!globalThis.crypto?.subtle) globalThis.crypto = webcrypto;

const toHex = (buf) =>
  [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");

async function signed(body, timestamp = "1790000000") {
  const pair = await webcrypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const message = new TextEncoder().encode(timestamp + body);
  const sig = await webcrypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, message);
  const pub = await webcrypto.subtle.exportKey("raw", pair.publicKey);
  return { signature: toHex(sig), publicKey: toHex(pub), timestamp };
}

test("올바른 서명은 통과한다", async () => {
  const body = JSON.stringify({ type: 1 });
  const s = await signed(body);
  assert.equal(await verifyRequest(body, s.signature, s.timestamp, s.publicKey), true);
});

test("본문이 한 글자라도 바뀌면 거부한다", async () => {
  const body = JSON.stringify({ type: 1 });
  const s = await signed(body);
  assert.equal(await verifyRequest(body + " ", s.signature, s.timestamp, s.publicKey), false);
});

test("타임스탬프가 바뀌면 거부한다 — 서명은 timestamp+body 에 걸려 있다", async () => {
  const body = JSON.stringify({ type: 2 });
  const s = await signed(body);
  assert.equal(await verifyRequest(body, s.signature, "1790000001", s.publicKey), false);
});

test("다른 키로 검증하면 거부한다", async () => {
  const body = JSON.stringify({ type: 2 });
  const s = await signed(body);
  const other = await signed(body);
  assert.equal(await verifyRequest(body, s.signature, s.timestamp, other.publicKey), false);
});

test("헤더가 없으면 거부한다 — 검증을 건너뛰지 않는다", async () => {
  const body = "{}";
  const s = await signed(body);
  assert.equal(await verifyRequest(body, null, s.timestamp, s.publicKey), false);
  assert.equal(await verifyRequest(body, s.signature, null, s.publicKey), false);
  assert.equal(await verifyRequest(body, s.signature, s.timestamp, null), false);
});

test("hex 가 아닌 값을 넣어도 예외 없이 거부한다", async () => {
  const body = "{}";
  const s = await signed(body);
  assert.equal(await verifyRequest(body, "zzzz", s.timestamp, s.publicKey), false);
  assert.equal(await verifyRequest(body, "abc", s.timestamp, s.publicKey), false); // 홀수 길이
  assert.equal(await verifyRequest(body, s.signature, s.timestamp, "00ff"), false); // 32바이트 아님
});
