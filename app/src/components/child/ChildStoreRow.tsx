import chevronIcon from "../../assets/child-chevron-right-icon.svg";
import type { ChildStoreListItem } from "../../hooks/childStoreList";
import { formatDistance } from "../../utils/childStoreStatus";
import { ChildCompanionBadge, ChildStoreStatusBadge } from "./ChildStoreBadges";

type ChildStoreRowProps = {
  store: ChildStoreListItem;
  onSelect: () => void;
};

/** 가게 목록 한 줄. 영업중이 아니면 사진을 어둡게 덮는다. */
function ChildStoreRow({ store, onSelect }: ChildStoreRowProps) {
  const isOpen = store.openStatus === "OPEN";

  return (
    <button
      type="button"
      onClick={onSelect}
      className="flex w-full items-center gap-3.5 py-3.5 text-left"
    >
      <span className="relative size-14 shrink-0 overflow-hidden rounded-xl">
        <img src={store.photoUrl} alt="" className="size-full object-cover" />
        {!isOpen && <span className="absolute inset-0 bg-black/30" />}
      </span>

      <span className="flex min-w-0 flex-1 flex-col gap-[3px]">
        <b className="truncate text-[15px] leading-[normal] font-bold text-[#1f2937]">{store.name}</b>
        <span className="truncate pb-[3px] text-xs leading-[normal] text-[#6b7280]">
          {store.addressShort} · {formatDistance(store.distanceMeters)}
        </span>
        <span className="flex gap-1.5">
          <ChildStoreStatusBadge status={store.openStatus} />
          <ChildCompanionBadge companion={store.companion} />
        </span>
      </span>

      <span className="flex size-[18px] shrink-0 items-center justify-center">
        <img src={chevronIcon} alt="" width={6} height={10} />
      </span>
    </button>
  );
}

export default ChildStoreRow;
