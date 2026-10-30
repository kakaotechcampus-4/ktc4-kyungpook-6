import { NavLink } from "react-router-dom";
import homeActiveIcon from "../../assets/tab-home-active-icon.svg";
import homeIcon from "../../assets/tab-home-icon.svg";
import ordersActiveIcon from "../../assets/tab-orders-active-icon.svg";
import ordersIcon from "../../assets/tab-orders-icon.svg";
import qrIcon from "../../assets/tab-qr-icon.svg";
import storeIcon from "../../assets/tab-store-icon.svg";
import storeInactiveIcon from "../../assets/tab-store-inactive-icon.svg";

/*
  점주 앱 아래 탭. path 가 null 인 탭은 화면이 아직 없어 누를 수 없다. 화면이 생기면 path 만 채운다.
  아이콘은 피그마에서 받은 색 그대로라 선택됐을 때(activeIcon, 노란색)와 아닐 때(icon, 회색)를 따로 둔다.
  activeIcon 이 없는 탭은 화면을 만들 때 그 화면 시안의 선택된 아이콘을 받아 채운다.
*/
const TABS: { label: string; icon: string; activeIcon?: string; path: string | null }[] = [
  { label: "홈", icon: homeIcon, path: "/" },
  { label: "주문", icon: ordersIcon, activeIcon: ordersActiveIcon, path: "/owner/orders" },
  // QR 화면은 탭 바 없이 전체 화면으로 떠서 선택된 아이콘이 보일 일이 없다.
  { label: "QR 확인", icon: qrIcon, path: "/owner/qr" },
  { label: "가게 관리", icon: storeInactiveIcon, activeIcon: storeIcon, path: "/owner/store" },
];

const TAB_CLASS = "flex w-16 flex-col items-center gap-1 text-xs leading-[normal] font-medium";

/** 화면 아래에 붙는 탭 바. */
function OwnerTabBar() {
  return (
    <nav className="fixed inset-x-0 bottom-0 flex justify-between border-t border-[#e5e7eb] bg-white px-9 pt-2.5 pb-[max(32px,env(safe-area-inset-bottom))]">
      {TABS.map(({ label, icon, activeIcon = icon, path }) =>
        path ? (
          <NavLink
            key={label}
            to={path}
            end
            className={({ isActive }) => `${TAB_CLASS} ${isActive ? "text-[#b8891a]" : "text-[#9ca3af]"}`}
          >
            {({ isActive }) => (
              <>
                <img src={isActive ? activeIcon : icon} alt="" width={24} height={24} />
                {label}
              </>
            )}
          </NavLink>
        ) : (
          <button key={label} type="button" disabled className={`${TAB_CLASS} text-[#9ca3af]`}>
            <img src={icon} alt="" width={24} height={24} />
            {label}
          </button>
        ),
      )}
    </nav>
  );
}

export default OwnerTabBar;
