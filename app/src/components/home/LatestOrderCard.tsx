import type { Order } from "../../services/order";
import { formatHourMinute } from "../../utils/format";
import ReceiveTypeBadge from "../order/ReceiveTypeBadge";

type LatestOrderCardProps = {
  order: Order;
  onCheckIn: () => void;
  onCancel: () => void;
};

/** 홈의 "최근 주문" 카드. 가장 최근에 들어온 대기 주문 한 건을 보여 준다. */
function LatestOrderCard({ order, onCheckIn, onCancel }: LatestOrderCardProps) {
  return (
    <article className="flex flex-col gap-1 rounded-2xl border border-[#e5e7eb] bg-white p-4">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-1.5 text-[17px] leading-[normal]">
          <span className="font-semibold text-[#3b82f6]">최근 주문</span>
          <span className="font-bold text-[#1f2937]">주문 #{order.orderNo}</span>
        </h2>
        <ReceiveTypeBadge receiveType={order.receiveType} />
      </div>
      <p className="pt-1.5 text-[15px] leading-[normal] text-[#1f2937]">{order.menuSummary}</p>
      <p className="text-sm leading-[normal] text-[#6b7280]">{formatHourMinute(order.orderedAt)} 주문</p>

      <div className="flex gap-[19px] pt-5">
        <button
          type="button"
          onClick={onCheckIn}
          className="flex h-10 flex-1 items-center justify-center rounded-[10px] bg-[#e3b23c] text-sm leading-[normal] font-semibold text-white"
        >
          직접 확인
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="flex h-10 items-center justify-center rounded-[10px] border border-[#e5e7eb] bg-white px-3.5 text-sm leading-[normal] font-semibold text-[#1f2937]"
        >
          주문 취소
        </button>
      </div>
    </article>
  );
}

export default LatestOrderCard;
