import CheckInMethodSheet from "../components/order/CheckInMethodSheet";
import OrderCard from "../components/order/OrderCard";
import OwnerTabBar from "../components/owner/OwnerTabBar";
import { useOwnerOrders } from "../hooks/ownerOrders";
import type { OrderStatus } from "../services/order";

const SUB_TABS: { status: OrderStatus; label: string }[] = [
  { status: "WAITING", label: "대기" },
  { status: "DONE", label: "완료" },
];

/** 점주 앱 "주문" 탭: 대기·완료 주문을 보고, 대기 주문을 체크인한다. */
function OwnerOrdersPage() {
  const orders = useOwnerOrders();
  const isWaiting = orders.status === "WAITING";

  return (
    <main className="min-h-screen bg-[#f7f8fa] px-5 pt-[env(safe-area-inset-top)] pb-[calc(84px+env(safe-area-inset-bottom))]">
      <h1 className="pt-3 pb-4 text-xl leading-[normal] font-bold text-[#1f2937]">주문</h1>

      <div role="tablist" className="mb-4 flex gap-5 border-b border-[#e5e7eb]">
        {SUB_TABS.map(({ status, label }) => {
          const isSelected = orders.status === status;
          return (
            <button
              key={status}
              type="button"
              role="tab"
              aria-selected={isSelected}
              onClick={() => orders.setStatus(status)}
              className={`-mb-px py-2.5 text-[15px] leading-[normal] font-semibold ${
                isSelected ? "border-b-2 border-[#1f2937] text-[#1f2937]" : "text-[#9ca3af]"
              }`}
            >
              {label} {orders.counts[status]}
            </button>
          );
        })}
      </div>

      {orders.notice && (
        <p role="status" className="mb-3 rounded-xl bg-[#fbf3dc] px-3.5 py-2.5 text-[13px] leading-[normal] text-[#8a6712]">
          {orders.notice}
        </p>
      )}

      {orders.orders.length === 0 ? (
        <p className="pt-10 text-center text-sm text-[#6b7280]">
          {isWaiting ? "대기 중인 주문이 없어요." : "완료된 주문이 없어요."}
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {orders.orders.map((order) => (
            <li key={order.orderId}>
              <OrderCard
                order={order}
                onCheckIn={isWaiting ? () => orders.openCheckIn(order) : undefined}
                onCancel={isWaiting ? () => orders.cancelOrder(order) : undefined}
              />
            </li>
          ))}
        </ul>
      )}

      <OwnerTabBar />

      {orders.checkInTarget && (
        <CheckInMethodSheet
          orderNo={orders.checkInTarget.orderNo}
          onSelectQr={orders.checkInByQr}
          onSelectManual={orders.checkInManually}
          onClose={orders.closeCheckIn}
        />
      )}
    </main>
  );
}

export default OwnerOrdersPage;
