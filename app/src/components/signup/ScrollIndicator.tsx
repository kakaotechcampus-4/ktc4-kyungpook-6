import { useEffect, useState } from "react";

/** 막대가 화면 위아래 끝에서 떨어지는 거리. */
const TRACK_INSET = 12;
/** 내용이 아주 길어도 막대가 이보다 짧아지지 않는다. */
const MIN_THUMB = 32;
/** 스크롤을 멈추고 이만큼 지나면 막대를 숨긴다(ms). */
const HIDE_DELAY = 800;

type Thumb = { visible: boolean; top: number; height: number };

/**
 * 가입 화면의 스크롤 막대. 기본 스크롤바는 숨기고(index.css 의 .signup-scroll) 대신 내용 위에 겹쳐 그린다.
 * 폭을 차지하지 않고, 스크롤할 때만 나타났다가 멈추면 사라진다. 스크롤할 내용이 없으면 그리지 않는다.
 */
function ScrollIndicator() {
  const [thumb, setThumb] = useState<Thumb>({ visible: false, top: 0, height: 0 });

  useEffect(() => {
    const root = document.documentElement;
    root.classList.add("signup-scroll");
    let hideTimer: ReturnType<typeof setTimeout> | undefined;

    const handleScroll = () => {
      const viewport = window.innerHeight;
      const total = root.scrollHeight;
      if (total <= viewport) return;
      const track = viewport - TRACK_INSET * 2;
      const height = Math.max(MIN_THUMB, (track * viewport) / total);
      const progress = window.scrollY / (total - viewport);
      setThumb({ visible: true, top: TRACK_INSET + (track - height) * progress, height });
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => setThumb((prev) => ({ ...prev, visible: false })), HIDE_DELAY);
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", handleScroll);
      clearTimeout(hideTimer);
      root.classList.remove("signup-scroll");
    };
  }, []);

  return (
    <div
      aria-hidden="true"
      className={[
        "pointer-events-none fixed right-0.5 z-50 w-1 rounded-full bg-[#1f2937]/30 transition-opacity duration-300",
        thumb.visible ? "opacity-100" : "opacity-0",
      ].join(" ")}
      style={{ top: thumb.top, height: thumb.height }}
    />
  );
}

export default ScrollIndicator;
