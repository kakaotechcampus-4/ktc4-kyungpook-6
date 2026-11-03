import logo from "../assets/good-radar-logo.png";
import LatestOrderCard from "../components/home/LatestOrderCard";
import OpenStatusCard from "../components/home/OpenStatusCard";
import OwnerTabBar from "../components/owner/OwnerTabBar";
import { useOwnerHome } from "../hooks/ownerHome";

type StatCardProps = { label: string; value: number; valueClassName: string };

/** "오늘 주문"의 숫자 칸. */
function StatCard({ label, value, valueClassName }: StatCardProps) {
  return (
    <div className="flex flex-1 flex-col gap-[5px] rounded-2xl border border-[#e5e7eb] bg-white px-3.5 pt-4 pb-3.5">
      <p className="text-sm leading-[normal] text-[#6b7280]">{label}</p>
      <p className={`text-[26px] leading-[normal] font-bold ${valueClassName}`}>{value}</p>
    </div>
  );
}

/** 점주 앱 "홈" 탭: 가게 이름·영업 상태·오늘 주문·최근 주문. */
function OwnerHomePage() {
  const home = useOwnerHome();

  return (
    <main className="min-h-screen bg-[#f7f8fa] px-5 pt-[env(safe-area-inset-top)] pb-[calc(84px+env(safe-area-inset-bottom))]">
      {home.isLoading && <p className="pt-10 text-center text-sm text-[#6b7280]">불러오는 중…</p>}
      {home.loadErrorMessage && <p className="pt-10 text-center text-sm text-[#6b7280]">{home.loadErrorMessage}</p>}

      {!home.isLoading && !home.loadErrorMessage && (
        <div className="flex flex-col gap-3.5">
          <header className="flex items-center justify-between pt-3">
            <div>
              <h1 className="text-xl leading-[normal] font-bold text-[#1f2937]">{home.storeName}</h1>
              <p className="text-sm leading-[normal] text-[#6b7280]">{home.addressRoad}</p>
            </div>
            <img src={logo} alt="" width={36} height={36} className="shrink-0" />
          </header>

          <OpenStatusCard
            isOpen={home.isOpen}
            mode={home.openMode}
            onModeChange={home.setOpenMode}
            onToggle={home.toggleOpen}
          />

          <section className="flex flex-col gap-2.5 pt-1.5">
            <h2 className="text-base leading-[normal] font-bold text-[#1f2937]">오늘 주문</h2>
            <div className="flex gap-2.5">
              <StatCard label="대기" value={home.waitingCount} valueClassName="text-[#b8891a]" />
              <StatCard label="완료" value={home.doneCount} valueClassName="text-[#1f2937]" />
            </div>
          </section>

          {home.notice && (
            <p role="status" className="rounded-xl bg-[#fbf3dc] px-3.5 py-2.5 text-[13px] leading-[normal] text-[#8a6712]">
              {home.notice}
            </p>
          )}

          {home.latestOrder && (
            <LatestOrderCard
              order={home.latestOrder}
              onCheckIn={home.checkInLatest}
              onCancel={home.cancelLatest}
            />
          )}
        </div>
      )}

      <OwnerTabBar />
    </main>
  );
}

export default OwnerHomePage;
