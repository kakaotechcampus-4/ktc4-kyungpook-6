import menuMascot from "../../assets/child-menu-mascot.svg";
import type { ChildStoreMenu } from "../../services/childStore";

type ChildMenuOptionProps = {
  menu: ChildStoreMenu;
  selected: boolean;
  onSelect: () => void;
};

/** 주문 화면의 메뉴 한 칸(라디오). 판매하지 않는 메뉴는 흐리게 하고 고를 수 없다. */
function ChildMenuOption({ menu, selected, onSelect }: ChildMenuOptionProps) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={!menu.onSale}
      onClick={onSelect}
      className={`flex w-full items-center justify-between rounded-xl border py-2.5 pr-4 pl-2.5 text-left disabled:opacity-45 ${
        selected ? "border-[#e2b33c] bg-[#fbf4dc]" : "border-[#e6e9ee] bg-white"
      }`}
    >
      <span className="flex min-w-0 items-center gap-3">
        <span className="flex size-13 shrink-0 items-center justify-center rounded-xl bg-[#fbf4dc]">
          <img src={menuMascot} alt="" width={34} height={34} />
        </span>
        <span className="flex min-w-0 flex-col gap-[5px] pb-0.5">
          <b className="truncate text-[15px] leading-[normal] font-bold text-[#1f2937]">{menu.name}</b>
          <span className="text-xs leading-[normal] text-[#6b7280]">{menu.onSale ? "1인분" : "오늘은 안 돼요"}</span>
        </span>
      </span>
      <span
        className={`size-5 shrink-0 rounded-full ${selected ? "border-6 border-[#e2b33c] bg-white" : "border border-[#a0a7b2]"}`}
      />
    </button>
  );
}

export default ChildMenuOption;
