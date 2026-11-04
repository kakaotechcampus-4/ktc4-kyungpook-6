import { useEffect, useState } from "react";

/** iOS Safari·웹뷰가 주는 나침반 값(북쪽 0°, 시계 방향). 표준 타입에는 없다. */
type CompassOrientationEvent = DeviceOrientationEvent & { webkitCompassHeading?: number };

/** iOS 13+ 는 방향 센서를 쓰기 전에 권한을 물어야 한다. 표준 타입에는 없다. */
type OrientationPermissionApi = { requestPermission?: () => Promise<"granted" | "denied"> };

/** 이보다 적게 바뀌면 다시 그리지 않는다(°). 센서 값이 계속 흔들려 화면이 쉴 새 없이 다시 그려지는 것을 막는다. */
const MIN_CHANGE_DEGREES = 3;

export type UseDeviceHeadingResult = {
  /**
   * 폰이 향한 방향(북쪽 0°, 시계 방향). 아직 받지 못했거나 센서가 없으면 null.
   * 화살표가 359° → 1° 에서 거꾸로 한 바퀴 돌지 않도록 360 을 넘거나 0 아래로 내려갈 수 있다(회전에만 쓴다).
   */
  heading: number | null;
  /** iOS 에서 방향 센서 권한을 묻는다. 버튼을 누른 때처럼 사용자 동작 안에서 불러야 한다. 다른 기기에서는 아무것도 안 한다. */
  requestPermission: () => Promise<void>;
};

/**
 * 폰이 향한 방향(나침반). 아동 홈 내 위치에 방향 화살표를 그린다.
 * iOS 는 webkitCompassHeading, Android 는 deviceorientationabsolute 의 alpha(반시계)를 시계 방향으로 바꿔 쓴다.
 * 북쪽 기준이 아닌 값(absolute 가 아닌 deviceorientation)은 버린다.
 */
export const useDeviceHeading = (): UseDeviceHeadingResult => {
  const [heading, setHeading] = useState<number | null>(null);

  useEffect(() => {
    const update = (next: number) =>
      setHeading((prev) => {
        if (prev === null) return next;
        // 지금 각도에서 가까운 쪽으로 도는 차이(-180 ~ 180).
        const diff = ((next - prev + 540) % 360) - 180;
        return Math.abs(diff) < MIN_CHANGE_DEGREES ? prev : prev + diff;
      });

    const handle = (event: Event) => {
      const { webkitCompassHeading, absolute, alpha } = event as CompassOrientationEvent;
      if (typeof webkitCompassHeading === "number") update(Math.round(webkitCompassHeading));
      else if (absolute && alpha !== null) update(Math.round((360 - alpha) % 360));
    };

    window.addEventListener("deviceorientationabsolute", handle);
    window.addEventListener("deviceorientation", handle);
    return () => {
      window.removeEventListener("deviceorientationabsolute", handle);
      window.removeEventListener("deviceorientation", handle);
    };
  }, []);

  return {
    heading,
    requestPermission: async () => {
      const api = (typeof DeviceOrientationEvent === "undefined"
        ? undefined
        : DeviceOrientationEvent) as unknown as OrientationPermissionApi | undefined;
      if (!api?.requestPermission) return;
      try {
        await api.requestPermission();
      } catch {
        // 거절하거나 사용자 동작 밖에서 부르면 실패한다. 화살표만 안 보일 뿐이라 그대로 둔다.
      }
    },
  };
};
