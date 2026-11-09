import type { Order } from "../../services/order";
import { formatHourMinute } from "../../utils/format";
import ReceiveTypeBadge from "./ReceiveTypeBadge";

type OrderCardProps = {
  order: Order;
  /** 접수 대기 "체크인". */
  onCheckIn: () => void;
  /** 접수 대기 "주문 취소". */
  onCancel: () => void;
  /** 조리 중 "조리 완료". */
  onComplete: () => void;
  /** 이 주문의 상태를 바꾸는 중이면 true. 버튼을 잠근다. */
  isChanging: boolean;
};

const BUTTON_BASE = "flex items-center justify-center rounded-[10px] leading-[normal] disabled:opacity-50";

/** 주문 탭의 주문 한 건. 상태마다 아래쪽(버튼·상태 글자)이 다르다 — 피그마 "주문" 접수 대기·조리 중·완료. */
function OrderCard({ order, onCheckIn, onCancel, onComplete, isChanging }: OrderCardProps) {
  // 완료 탭(조리 완료·취소)은 끝난 주문이라 글자를 흐리게 한다.
  const isFinished = order.status === "DONE" || order.status === "CANCELED";
  const textColor = isFinished ? "text-[#6b7280]" : "text-[#1f2937]";

  return (
    <article
      className={`flex flex-col gap-1 rounded-2xl border border-[#e5e7eb] p-4 ${
        order.status === "RECEIVED" ? "bg-white" : isFinished ? "bg-[#fafbfc]" : ""
      }`}
    >
      <div className="flex items-center justify-between">
        <h2 className={`text-[17px] leading-[normal] font-bold ${textColor}`}>주문 #{order.orderId}</h2>
        <ReceiveTypeBadge receiveType={order.receiveType} />
      </div>
      <p className={`text-[15px] leading-[normal] ${textColor} ${order.status === "RECEIVED" ? "pt-1.5" : "pt-1"}`}>
        {order.menuName}
      </p>

      {order.status === "RECEIVED" && (
        <>
          <p className="text-sm leading-[normal] text-[#6b7280]">{formatHourMinute(order.orderedAt)} 주문</p>
          <div className="flex gap-[19px] pt-2.5">
            <button
              type="button"
              onClick={onCheckIn}
              disabled={isChanging}
              className={`${BUTTON_BASE} h-10 flex-1 bg-[#e3b23c] text-sm font-semibold text-white`}
            >
              체크인
            </button>
            <button
              type="button"
              onClick={onCancel}
              disabled={isChanging}
              className={`${BUTTON_BASE} h-10 border border-[#e5e7eb] bg-white px-3.5 text-sm font-semibold text-[#1f2937]`}
            >
              주문 취소
            </button>
          </div>
        </>
      )}

      {order.status === "COOKING" && (
        <>
          <StatusRow
            time={`${formatHourMinute(order.cookingStartedAt ?? order.orderedAt)} 확인`}
            label="조리 중"
            labelColor="text-[#e08a00]"
            className="pb-2.5"
          />
          <button
            type="button"
            onClick={onComplete}
            disabled={isChanging}
            className={`${BUTTON_BASE} h-11 w-full bg-[#e3b23c] text-[15px] font-bold text-white`}
          >
            조리 완료
          </button>
        </>
      )}

      {order.status === "DONE" && (
        <StatusRow
          time={`${formatHourMinute(order.completedAt ?? order.orderedAt)} 완료`}
          label="조리 완료"
          labelColor="text-[#1f9d55]"
        />
      )}

      {order.status === "CANCELED" && (
        <StatusRow
          time={`${formatHourMinute(order.canceledAt ?? order.orderedAt)} 주문 취소`}
          label="주문 취소"
          labelColor="text-[#e5484d]"
        />
      )}
    </article>
  );
}

type StatusRowProps = {
  time: string;
  label: string;
  labelColor: string;
  className?: string;
};

/** 시각과 상태 글자 한 줄. 조리 중·완료 카드가 쓴다. */
function StatusRow({ time, label, labelColor, className = "" }: StatusRowProps) {
  return (
    <div className={`flex items-center justify-between ${className}`}>
      <span className="text-sm leading-[normal] text-[#6b7280]">{time}</span>
      <span className={`text-[13px] leading-[normal] font-semibold ${labelColor}`}>{label}</span>
    </div>
  );
}

export default OrderCard;
