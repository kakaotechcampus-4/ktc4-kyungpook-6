import { useEffect, useState } from "react";
import QRCode from "qrcode";

type ChildQrCodeProps = {
  /** QR 에 담을 문자열. */
  value: string;
  /** 그릴 크기(px). */
  size: number;
};

/** 문자열을 QR 그림으로 그린다. 기기 안에서 그려서 인터넷이 필요 없다. */
function ChildQrCode({ value, size }: ChildQrCodeProps) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // 점주 폰 카메라가 잘 읽게 화면 배율만큼 크게 그리고 표시 크기로 줄인다.
    QRCode.toDataURL(value, { width: size * 3, margin: 1, errorCorrectionLevel: "M" })
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        if (!cancelled) setDataUrl(null);
      });
    return () => {
      cancelled = true;
    };
  }, [value, size]);

  if (!dataUrl) return <div style={{ width: size, height: size }} />;
  return <img src={dataUrl} alt="내 QR 코드" width={size} height={size} />;
}

export default ChildQrCode;
