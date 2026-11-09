import { Navigate } from "react-router-dom";
import dineIcon from "../assets/child-dine-icon.svg";
import ownerNoteIcon from "../assets/child-owner-note-icon.svg";
import peopleIcon from "../assets/child-people-icon.svg";
import phoneIcon from "../assets/child-phone-icon.svg";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import { ChildStoreStatusBadge } from "../components/child/ChildStoreBadges";
import ChildStoreHoursRow from "../components/child/ChildStoreHoursRow";
import ChildStoreMenuCard from "../components/child/ChildStoreMenuCard";
import { useChildStoreDetail } from "../hooks/childStoreDetail";
import type { CompanionPolicy } from "../services/childStore";
import { formatDistance } from "../utils/childStoreStatus";

const COMPANION_TEXT: Record<CompanionPolicy, string> = {
  NONE: "본인만 먹을 수 있어요",
  ONE: "1명까지 같이 먹을 수 있어요",
  TWO: "2명까지 같이 먹을 수 있어요",
};

const INFO_ROW_CLASS = "flex items-center gap-3 border-b border-[#e6e9ee] px-4 py-3.5 text-sm leading-5 text-[#1f2937] last:border-b-0";

/** 아동 가게 정보. 영업시간·이용 방법·메뉴를 보고, 영업중이면 주문하러 간다. */
function ChildStoreDetailPage() {
  const { store, isLoading, openStatus, today, isHoursExpanded, toggleHours, canOrder, goOrder } = useChildStoreDetail();

  // 없는 가게 번호로 들어오면 목록으로 돌려보낸다.
  if (isLoading) return <main className="min-h-screen bg-white" />;
  if (!store) return <Navigate to="/child/stores" replace />;

  const receiveTypes = [store.takeoutAvailable && "포장", store.dineInAvailable && "매장 식사"].filter(Boolean);

  return (
    <main className="min-h-screen bg-white pt-[env(safe-area-inset-top)] pb-[calc(94px+env(safe-area-inset-bottom))]">
      <div className="flex h-12 items-center px-5">
        <BackButton />
      </div>

      <div className="px-5 pt-1">
        <div className="relative flex h-[170px] items-end overflow-hidden rounded-[18px] p-3.5">
          <img src={store.photoUrl} alt="" className="absolute inset-0 size-full object-cover" />
          {openStatus !== "OPEN" && <span className="absolute inset-0 bg-black/30" />}
          <span className="relative">
            <ChildStoreStatusBadge status={openStatus} />
          </span>
        </div>

        <h1 className="pt-[18px] text-[22px] leading-[normal] font-bold text-[#1f2937]">{store.name}</h1>
        <p className="pt-1 pb-5 text-[13px] leading-[normal] text-[#6b7280]">
          {store.addressShort} · 내 위치에서 {formatDistance(store.distanceMeters)}
        </p>

        <div className="rounded-2xl border border-[#e6e9ee]">
          <ChildStoreHoursRow
            businessHours={store.businessHours}
            today={today}
            expanded={isHoursExpanded}
            onToggle={toggleHours}
          />
          <div className={INFO_ROW_CLASS}>
            <img src={dineIcon} alt="" width={20} height={20} />
            <span className="flex gap-1.5">
              {receiveTypes.map((label, index) => (
                <span key={String(label)} className="flex gap-1.5">
                  {index > 0 && <span className="text-[#a0a7b2]">·</span>}
                  {label}
                </span>
              ))}
            </span>
          </div>
          <div className={INFO_ROW_CLASS}>
            <img src={peopleIcon} alt="" width={20} height={20} />
            {COMPANION_TEXT[store.companion]}
          </div>
          <div className={INFO_ROW_CLASS}>
            <img src={phoneIcon} alt="" width={20} height={20} />
            <a href={`tel:${store.phone}`}>{store.phone}</a>
          </div>
        </div>

        {store.ownerNote && (
          <div className="mt-3 flex gap-2.5 rounded-[14px] bg-[#eaf0fd] px-4 py-3.5">
            <img src={ownerNoteIcon} alt="" width={18} height={18} className="mt-px h-[18px] shrink-0" />
            <p className="min-w-0">
              <span className="block text-[11px] leading-[17.6px] font-bold text-[#3f66cc]">사장님 한마디</span>
              <span className="block text-[13px] leading-[20.8px] text-[#1f2937]">{store.ownerNote}</span>
            </p>
          </div>
        )}

        <h2 className="pt-6 pb-2.5 text-[15px] leading-[normal] font-bold text-[#1f2937]">급식카드로 먹을 수 있는 메뉴</h2>
        <ul className="flex flex-col gap-2.5">
          {store.menus.map((menu) => (
            <li key={menu.menuId}>
              <ChildStoreMenuCard menu={menu} />
            </li>
          ))}
        </ul>
      </div>

      <div className="fixed inset-x-0 bottom-0 bg-white px-5 pt-3 pb-[calc(30px+env(safe-area-inset-bottom))]">
        <PrimaryButton disabled={!canOrder} onClick={goOrder}>
          주문하기
        </PrimaryButton>
      </div>
    </main>
  );
}

export default ChildStoreDetailPage;
