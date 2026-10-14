import { useEffect, useRef, useState } from "react";
import QrScanner from "qr-scanner";

const CAMERA_DENIED_MESSAGE = "카메라 권한이 없어요. 설정에서 카메라를 허용해주세요.";
const CAMERA_UNAVAILABLE_MESSAGE = "카메라를 켜지 못했어요. 잠시 후 다시 시도해 주세요.";

export type UseQrScannerResult = {
  /** 카메라 화면(<video>)을 넣을 자리에 붙인다. 이 요소를 꽉 채운다. */
  containerRef: React.RefObject<HTMLDivElement | null>;
  /** 카메라를 켜지 못했을 때의 문구. 켜졌으면 null. */
  cameraError: string | null;
};

/**
 * 뒤 카메라로 QR 을 계속 읽는다. 읽을 때마다 onDecode 에 읽은 문자열을 **그대로** 넘긴다.
 *
 * 같은 QR 이 화면에 머물면 여러 번 불린다. 중복을 거르는 건 부르는 쪽 몫이다.
 * 화면을 떠나면 카메라를 끈다.
 *
 * <video> 는 스캐너마다 새로 만든다. qr-scanner 는 끌 때 300ms 뒤에 그 video 의 스트림을 멈추는데,
 * video 를 같이 쓰면 화면이 다시 붙을 때(StrictMode 등) 새 스캐너의 스트림까지 꺼 버린다.
 *
 * 웹뷰의 getUserMedia 로 카메라를 쓴다. 그래서 Android 는 CAMERA 권한(AndroidManifest),
 * iOS 는 NSCameraUsageDescription(Info.plist)이 있어야 하고, 웹에서는 https 나 localhost 에서만 켜진다.
 */
export const useQrScanner = (onDecode: (data: string) => void): UseQrScannerResult => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);

  // 스캐너를 다시 만들지 않고도 최신 onDecode 를 부르게 한다.
  const onDecodeRef = useRef(onDecode);
  useEffect(() => {
    onDecodeRef.current = onDecode;
  }, [onDecode]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const video = document.createElement("video");
    video.className = "size-full object-cover";
    video.muted = true;
    // iOS 는 playsinline 이 없으면 전체 화면 플레이어로 띄운다.
    video.setAttribute("playsinline", "");
    container.appendChild(video);

    const scanner = new QrScanner(video, (result) => onDecodeRef.current(result.data), {
      preferredCamera: "environment",
      returnDetailedScanResult: true,
    });

    let destroyed = false;
    scanner.start().catch((error: unknown) => {
      if (destroyed) return;
      const denied = error instanceof DOMException && error.name === "NotAllowedError";
      setCameraError(denied ? CAMERA_DENIED_MESSAGE : CAMERA_UNAVAILABLE_MESSAGE);
    });

    return () => {
      destroyed = true;
      scanner.destroy();
      video.remove();
    };
  }, []);

  return { containerRef, cameraError };
};
