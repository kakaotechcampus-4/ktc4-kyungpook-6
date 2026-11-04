import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

/** 막대가 목록 위아래 끝에서 떨어지는 거리. */
const TRACK_INSET = 4;
/** 목록이 아주 길어도 막대가 이보다 짧아지지 않는다. */
const MIN_THUMB = 24;
/** 스크롤을 멈추고 이만큼 지나면 막대를 숨긴다(ms). */
const HIDE_DELAY = 800;

type Thumb = { visible: boolean; top: number; height: number };

type OverlayScrollListProps = {
  /** ul 에 붙일 클래스(높이 상한 등). */
  className?: string;
  children: ReactNode;
};

/**
 * 안에서 스크롤되는 목록(ul). 기본 스크롤바는 숨기고 오른쪽 끝에 얇은 막대를 겹쳐 그린다.
 * 폭을 차지하지 않고, 스크롤할 때만 나타났다가 멈추면 사라진다.
 */
function OverlayScrollList({ className, children }: OverlayScrollListProps) {
  const listRef = useRef<HTMLUListElement>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [thumb, setThumb] = useState<Thumb>({ visible: false, top: 0, height: 0 });

  useEffect(() => () => clearTimeout(hideTimerRef.current), []);

  const handleScroll = () => {
    const list = listRef.current;
    if (!list || list.scrollHeight <= list.clientHeight) return;
    const track = list.clientHeight - TRACK_INSET * 2;
    const height = Math.max(MIN_THUMB, (track * list.clientHeight) / list.scrollHeight);
    const progress = list.scrollTop / (list.scrollHeight - list.clientHeight);
    setThumb({ visible: true, top: TRACK_INSET + (track - height) * progress, height });
    clearTimeout(hideTimerRef.current);
    hideTimerRef.current = setTimeout(() => setThumb((prev) => ({ ...prev, visible: false })), HIDE_DELAY);
  };

  return (
    <div className="relative w-full min-h-0">
      <ul
        ref={listRef}
        onScroll={handleScroll}
        className={["overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden", className]
          .filter(Boolean)
          .join(" ")}
      >
        {children}
      </ul>
      <span
        aria-hidden="true"
        className={[
          "pointer-events-none absolute -right-2 w-1 rounded-full bg-[#1f2937]/30 transition-opacity duration-300",
          thumb.visible ? "opacity-100" : "opacity-0",
        ].join(" ")}
        style={{ top: thumb.top, height: thumb.height }}
      />
    </div>
  );
}

export default OverlayScrollList;
