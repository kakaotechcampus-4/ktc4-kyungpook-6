import type { OrderReceiveType } from "../../services/order";

const RECEIVE_BADGES: Record<OrderReceiveType, { label: string; className: string }> = {
  TAKEOUT: { label: "포장", className: "bg-[#fbf3dc] text-[#b8891a]" },
  DINE_IN: { label: "매장", className: "bg-[#eaf1fe] text-[#4e82ee]" },
};

/** 받는 방법(포장·매장) 배지. */
function ReceiveTypeBadge({ receiveType }: { receiveType: OrderReceiveType }) {
  const badge = RECEIVE_BADGES[receiveType];
  return (
    <span className={`flex h-6 items-center rounded-md px-2 text-xs leading-[normal] font-semibold ${badge.className}`}>
      {badge.label}
    </span>
  );
}

export default ReceiveTypeBadge;
