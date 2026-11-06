import CheckInMethodSheet from "../components/order/CheckInMethodSheet";
import OrderCard from "../components/order/OrderCard";
import OwnerTabBar from "../components/owner/OwnerTabBar";
import { useOwnerOrders } from "../hooks/ownerOrders";
import type { OrderTab } from "../services/order";

const SUB_TABS: { tab: OrderTab; label: string; emptyText: string }[] = [
  { tab: "RECEIVED", label: "접수 대기", emptyText: "접수 대기 중인 주문이 없어요." },
  { tab: "COOKING", label: "조리 중", emptyText: "조리 중인 주문이 없어요." },
  { tab: "DONE", label: "완료", emptyText: "완료된 주문이 없어요." },
];

/** 점주 앱 "주문" 탭: 접수 대기 주문을 체크인·취소하고, 조리 중 주문을 조리 완료한다. */
function OwnerOrdersPage() {
  const orders = useOwnerOrders();
  const current = SUB_TABS.find(({ tab }) => tab === orders.tab) ?? SUB_TABS[0];

  return (
    <main className="min-h-screen bg-[#f7f8fa] px-5 pt-[env(safe-area-inset-top)] pb-[calc(84px+env(safe-area-inset-bottom))]">
      <h1 className="pt-3 pb-4 text-xl leading-[normal] font-bold text-[#1f2937]">주문</h1>

      <div role="tablist" className="mb-4 flex gap-5 border-b border-[#e5e7eb]">
        {SUB_TABS.map(({ tab, label }) => {
          const isSelected = orders.tab === tab;
          return (
            <button
              key={tab}
              type="button"
              role="tab"
              aria-selected={isSelected}
              onClick={() => orders.setTab(tab)}
              className={`-mb-px py-2.5 text-[15px] leading-[normal] font-semibold ${
                isSelected ? "border-b-2 border-[#1f2937] text-[#1f2937]" : "text-[#9ca3af]"
              }`}
            >
              {label} {orders.counts[tab]}
            </button>
          );
        })}
      </div>

      {orders.notice && (
        <p role="status" className="mb-3 rounded-xl bg-[#fbf3dc] px-3.5 py-2.5 text-[13px] leading-[normal] text-[#8a6712]">
          {orders.notice}
        </p>
      )}

      {orders.isLoading ? null : orders.orders.length === 0 ? (
        <p className="pt-10 text-center text-sm text-[#6b7280]">{current.emptyText}</p>
      ) : (
        // 피그마 간격: 접수 대기 카드는 12px, 조리 중·완료 카드는 10px.
        <ul className={`flex flex-col ${orders.tab === "RECEIVED" ? "gap-3" : "gap-2.5"}`}>
          {orders.orders.map((order) => (
            <li key={order.orderId}>
              <OrderCard
                order={order}
                onCheckIn={() => orders.openCheckIn(order)}
                onCancel={() => orders.cancelOrder(order)}
                onComplete={() => orders.completeOrder(order)}
                isChanging={orders.changingOrderId === order.orderId}
              />
            </li>
          ))}
        </ul>
      )}

      <OwnerTabBar />

      {orders.checkInTarget && (
        <CheckInMethodSheet
          orderNo={orders.checkInTarget.orderId}
          onSelectQr={orders.checkInByQr}
          onSelectManual={orders.checkInManually}
          onClose={orders.closeCheckIn}
        />
      )}
    </main>
  );
}

export default OwnerOrdersPage;
