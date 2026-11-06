import { useRef, useState } from "react";
import type { PointerEvent } from "react";
import chevronIcon from "../../assets/child-chevron-right-icon.svg";
import OverlayScrollList from "../ui/OverlayScrollList";
import type { ChildStoreListItem } from "../../hooks/childStoreList";
import { findMatchedMenuName, splitByKeyword } from "../../utils/childSearch";
import { formatDistance } from "../../utils/childStoreStatus";
import { ChildCompanionBadge, ChildStoreStatusBadge } from "./ChildStoreBadges";

/** 검색어에 맞는 부분만 파랗게. */
function Highlighted({ text, query }: { text: string; query: string }) {
  const parts = splitByKeyword(text, query);
  if (!parts) return <>{text}</>;
  const [before, match, after] = parts;
  return (
    <>
      {before}
      <span className="text-[#3f66cc]">{match}</span>
      {after}
    </>
  );
}

type ChildSearchSheetProps = {
  /** 목록 위 제목("'치킨' 검색 결과 5곳"). 없으면 그리지 않는다(핀을 눌러 한 곳만 띄울 때). */
  title?: string;
  /** 강조할 검색어. 비어 있으면 강조하지 않는다. */
  query: string;
  stores: ChildStoreListItem[];
  selectedStoreId: number | null;
  /** 줄을 누르면 고른다(지도가 그 가게로 옮겨 간다). */
  onSelect: (storeId: number) => void;
  /** 오른쪽 화살표를 누르면 가게 정보로 간다. */
  onOpen: (storeId: number) => void;
};

/** 회색 바를 이만큼(px) 넘게 끌면 접거나 편다. 이보다 적게 움직이면 누른 것으로 본다. */
const DRAG_THRESHOLD = 30;
const TAP_SLOP = 6;

/**
 * 홈 지도 아래 가게 목록. 검색하면 결과를 가까운 순으로, 핀을 누르면 그 가게 한 곳을 띄운다.
 * 고른 가게는 노랗게 칠하고, 영업중이 아닌 가게는 사진을 어둡게 덮는다(가게 목록과 같다).
 *
 * 위 회색 바를 끌어내리거나 누르면 목록을 접는다. 지도는 그대로 두고, 접은 뒤에도 회색 바와 제목 줄은 남아
 * 다시 끌어올리거나 누르면 편다. 검색어나 고른 가게가 바뀌면 화면이 key 로 새로 그려 다시 펼쳐진다.
 */
function ChildSearchSheet({ title, query, stores, selectedStoreId, onSelect, onOpen }: ChildSearchSheetProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [dragOffset, setDragOffset] = useState(0);
  const dragStartRef = useRef<number | null>(null);

  const handlePointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    dragStartRef.current = event.clientY;
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const handlePointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    if (dragStartRef.current === null || collapsed) return;
    // 펼친 상태에서 아래로 끄는 만큼만 따라 내려온다.
    setDragOffset(Math.max(0, event.clientY - dragStartRef.current));
  };
  const handlePointerUp = (event: PointerEvent<HTMLButtonElement>) => {
    if (dragStartRef.current === null) return;
    const moved = event.clientY - dragStartRef.current;
    dragStartRef.current = null;
    setDragOffset(0);
    if (Math.abs(moved) <= TAP_SLOP) setCollapsed((prev) => !prev);
    else if (moved > DRAG_THRESHOLD) setCollapsed(true);
    else if (moved < -DRAG_THRESHOLD) setCollapsed(false);
  };

  // 접었을 때 남는 제목. 핀으로 한 곳만 띄웠으면 가게 이름을 쓴다.
  const heading = title ?? stores[0]?.name;

  return (
    <section
      aria-label={title ?? "고른 가게"}
      className="flex w-full flex-col items-center gap-2.5 rounded-[20px] bg-white px-4 pt-2.5 pb-4 drop-shadow-[0px_-4px_12px_rgba(30,38,51,0.14)]"
      style={dragOffset > 0 ? { transform: `translateY(${dragOffset}px)` } : undefined}
    >
      <button
        type="button"
        aria-expanded={!collapsed}
        aria-label={collapsed ? "목록 펴기" : "목록 접기"}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={() => {
          dragStartRef.current = null;
          setDragOffset(0);
        }}
        className="flex w-full touch-none flex-col items-center gap-2.5"
      >
        <span aria-hidden="true" className="h-1 w-9 rounded-sm bg-[#e6e9ee]" />
        {(title || collapsed) && heading && (
          <span className="w-full pt-0.5 text-left text-[13px] leading-[normal] font-medium text-[#6b7280]">
            {heading}
          </span>
        )}
      </button>

      {collapsed ? null : stores.length > 0 ? (
        <OverlayScrollList className="max-h-[min(260px,35dvh)] w-full">
          {stores.map((store) => {
            const selected = store.storeId === selectedStoreId;
            const menuName = findMatchedMenuName(store, query);
            return (
              <li
                key={store.storeId}
                className={`flex items-center gap-1 rounded-xl px-1.5 ${selected ? "bg-[#fbf4dc]" : ""}`}
              >
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() => onSelect(store.storeId)}
                  className="flex min-w-0 flex-1 items-center gap-3 py-2.5 text-left"
                >
                  <span className="relative size-12 shrink-0 overflow-hidden rounded-[10px]">
                    <img src={store.photoUrl} alt="" className="size-full object-cover" />
                    {store.openStatus !== "OPEN" && <span className="absolute inset-0 bg-black/30" />}
                  </span>
                  <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                    <b className="truncate text-[15px] leading-[normal] font-bold text-[#1f2937]">
                      <Highlighted text={store.name} query={query} />
                    </b>
                    <span className="truncate pb-[3.5px] text-xs leading-[normal] text-[#6b7280]">
                      {menuName && (
                        <>
                          <span className="font-bold">
                            <Highlighted text={menuName} query={query} />
                          </span>
                          {" · "}
                        </>
                      )}
                      {store.addressShort} · {formatDistance(store.distanceMeters)}
                    </span>
                    <span className="flex gap-1.5">
                      <ChildStoreStatusBadge status={store.openStatus} />
                      <ChildCompanionBadge companion={store.companion} />
                    </span>
                  </span>
                </button>
                <button
                  type="button"
                  aria-label={`${store.name} 가게 정보`}
                  onClick={() => onOpen(store.storeId)}
                  className="flex size-8 shrink-0 items-center justify-center rounded-lg"
                >
                  <img src={chevronIcon} alt="" width={6} height={10} />
                </button>
              </li>
            );
          })}
        </OverlayScrollList>
      ) : (
        <p className="w-full py-6 text-center text-sm leading-[normal] text-[#6b7280]">찾는 가게가 없어요.</p>
      )}
    </section>
  );
}

export default ChildSearchSheet;
