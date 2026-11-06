import companionIcon from "../../assets/child-companion-icon.svg";
import type { CompanionPolicy } from "../../services/childStore";
import { CHILD_STORE_STATUS_LABEL } from "../../utils/childStoreStatus";
import type { ChildStoreOpenStatus } from "../../utils/childStoreStatus";

const BADGE_CLASS = "flex h-[19px] items-center gap-[5px] rounded-[10px] px-2 text-[11px] leading-[normal] font-medium";

/** 영업중만 초록, 나머지(쉬는중·휴무중·영업전·영업종료)는 모두 빨강. */
export function ChildStoreStatusBadge({ status }: { status: ChildStoreOpenStatus }) {
  const isOpen = status === "OPEN";
  return (
    <span className={`${BADGE_CLASS} ${isOpen ? "bg-[#e3f5ec] text-[#22a06b]" : "bg-[#fae1e4] text-[#f93446]"}`}>
      <span className={`size-[5px] rounded-full ${isOpen ? "bg-[#22a06b]" : "bg-[#f93446]"}`} />
      {CHILD_STORE_STATUS_LABEL[status]}
    </span>
  );
}

const COMPANION_LABEL: Record<Exclude<CompanionPolicy, "NONE">, string> = {
  ONE: "동반1인",
  TWO: "동반2인",
};

/** 함께 먹을 수 있는 인원 배지. 아이 본인만이면 그리지 않는다. */
export function ChildCompanionBadge({ companion }: { companion: CompanionPolicy }) {
  if (companion === "NONE") return null;
  return (
    <span className={`${BADGE_CLASS} bg-[#fef3d9] text-[#c18700]`}>
      <img src={companionIcon} alt="" width={12} height={12} />
      {COMPANION_LABEL[companion]}
    </span>
  );
}
