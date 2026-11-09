import { Preferences } from "@capacitor/preferences";
import { postRequest } from "./api";

/*
  내 QR 에 담을 문자열("v1.<토큰>")을 폰에 저장한다.
  서버는 이 값을 발급할 때 한 번만 주고(해시만 보관), 새로 발급하면 옛 QR 은 바로 못 쓴다.
  그래서 한 번 받으면 저장해 두고 계속 그 값으로 그린다 — 화면을 열 때마다 받으면 점주가 찍는 사이에 QR 이 바뀐다.
  보여 줄 때는 인터넷이 필요 없다.
*/
const QR_PAYLOAD_KEY = "child.qrPayload";

/** 내 QR 발급(POST /api/children/me/qr-token). 부를 때마다 새 QR 이고 옛 QR 은 못 쓰게 된다. 아동 토큰이 필요하다. */
const issueQrPayload = async () => {
  const { qrPayload } = await postRequest<{ qrPayload: string }>("/api/children/me/qr-token", undefined);
  return qrPayload;
};

/** 저장해 둔 내 QR 값. 없으면 한 번 발급받아 저장한다. */
export const getOrIssueQrPayload = async () => {
  const { value } = await Preferences.get({ key: QR_PAYLOAD_KEY });
  if (value) return value;
  const issued = await issueQrPayload();
  await Preferences.set({ key: QR_PAYLOAD_KEY, value: issued });
  return issued;
};

/** 저장한 QR 값을 지운다. 다른 카드로 인증하면 앞 아동의 QR 이 남지 않게 부른다. */
export const clearQrPayload = () => Preferences.remove({ key: QR_PAYLOAD_KEY });
