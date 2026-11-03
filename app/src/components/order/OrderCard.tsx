import type { Order } from "../../services/order";
import { formatHourMinute } from "../../utils/format";
import ReceiveTypeBadge from "./ReceiveTypeBadge";

type OrderCardProps = {
  order: Order;
  /** 대기 주문에만 준다. 없으면 버튼 줄을 그리지 않는다. */
  onCheckIn?: () => void;
  onCancel?: () => void;
};

/** 주문 탭의 주문 한 건. */
function OrderCard({ order, onCheckIn, onCancel }: OrderCardProps) {
  return (
    <article className="flex flex-col gap-1 rounded-2xl border border-[#e5e7eb] bg-white p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-[17px] leading-[normal] font-bold text-[#1f2937]">주문 #{order.orderNo}</h2>
        <ReceiveTypeBadge receiveType={order.receiveType} />
      </div>
      <p className="pt-1.5 text-[15px] leading-[normal] text-[#1f2937]">{order.menuSummary}</p>
      <p className="text-sm leading-[normal] text-[#6b7280]">{formatHourMinute(order.orderedAt)} 주문</p>

      {onCheckIn && onCancel && (
        <div className="flex gap-[19px] pt-2.5">
          <button
            type="button"
            onClick={onCheckIn}
            className="flex h-10 flex-1 items-center justify-center rounded-[10px] bg-[#e3b23c] text-sm leading-[normal] font-semibold text-white"
          >
            체크인
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="flex h-10 items-center justify-center rounded-[10px] border border-[#e5e7eb] bg-white px-3.5 text-sm leading-[normal] font-semibold text-[#1f2937]"
          >
            주문 취소
          </button>
        </div>
      )}
    </article>
  );
}

export default OrderCard;
