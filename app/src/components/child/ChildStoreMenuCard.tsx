import menuMascot from "../../assets/child-menu-mascot.svg";
import type { ChildStoreMenu } from "../../services/childStore";

/** 가게 정보의 메뉴 한 칸. 판매하지 않는 메뉴는 흐리게 하고 "오늘은 안 돼요"를 붙인다. */
function ChildStoreMenuCard({ menu }: { menu: ChildStoreMenu }) {
  return (
    <div
      className={`flex items-center gap-3.5 rounded-[14px] border border-[#e6e9ee] p-3 ${menu.onSale ? "" : "opacity-45"}`}
    >
      <span className="flex size-15 shrink-0 items-center justify-center rounded-xl bg-[#fbf4dc]">
        <img src={menuMascot} alt="" width={34} height={34} />
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <b className="truncate text-[15px] leading-[normal] font-bold text-[#1f2937]">{menu.name}</b>
        <span className="text-xs leading-[normal] text-[#6b7280]">1인분</span>
      </span>
      {!menu.onSale && (
        <span className="shrink-0 rounded-lg bg-[#f1f3f6] px-2 py-1 text-[11px] leading-[normal] font-medium text-[#6b7280]">
          오늘은 안 돼요
        </span>
      )}
    </div>
  );
}

export default ChildStoreMenuCard;
