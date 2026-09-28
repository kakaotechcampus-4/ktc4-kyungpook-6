/**
 * 디스코드가 보낸 요청인지 Ed25519 서명으로 검증한다.
 *
 * 이 엔드포인트는 인터넷에 열려 있다. 검증을 건너뛰면 누구나 우리 봇 이름으로
 * 예약을 걸거나 메시지를 보낼 수 있다. 그래서 **검증 실패는 무조건 401** 이고,
 * 헤더가 없는 요청도 같이 막는다.
 *
 * ⚠️ Workers 의 WebCrypto 는 한때 알고리즘 이름을 'NODE-ED25519' 로만 받았다.
 *    지금은 표준 이름 'Ed25519' 를 쓰는데, 런타임에 따라 아직 옛 이름만 되는 경우가
 *    있어 둘 다 시도한다. 어느 쪽으로 통했는지는 동작에 영향이 없다.
 */

const ALGOS = ["Ed25519", "NODE-ED25519"];

function hexToBytes(hex) {
  if (typeof hex !== "string" || hex.length % 2 !== 0) return null;
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i += 1) {
    const byte = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    if (Number.isNaN(byte)) return null;
    out[i] = byte;
  }
  return out;
}

/**
 * @param {string} rawBody 요청 본문 문자열 (JSON.parse 하기 전의 것 그대로)
 * @param {string} signature X-Signature-Ed25519 헤더
 * @param {string} timestamp X-Signature-Timestamp 헤더
 * @param {string} publicKey 앱의 PUBLIC KEY (hex)
 */
export async function verifyRequest(rawBody, signature, timestamp, publicKey) {
  if (!signature || !timestamp || !publicKey) return false;

  const sigBytes = hexToBytes(signature);
  const keyBytes = hexToBytes(publicKey);
  if (!sigBytes || !keyBytes || keyBytes.length !== 32) return false;

  const message = new TextEncoder().encode(timestamp + rawBody);

  for (const name of ALGOS) {
    try {
      const key = await crypto.subtle.importKey("raw", keyBytes, { name }, false, ["verify"]);
      return await crypto.subtle.verify({ name }, key, sigBytes, message);
    } catch {
      // 이 런타임이 그 알고리즘 이름을 모르는 것이므로 다음 이름으로 넘어간다.
    }
  }
  return false;
}
