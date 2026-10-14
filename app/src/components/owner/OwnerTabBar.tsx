import { NavLink } from "react-router-dom";
import homeIcon from "../../assets/tab-home-icon.svg";
import ordersIcon from "../../assets/tab-orders-icon.svg";
import qrIcon from "../../assets/tab-qr-icon.svg";
import storeIcon from "../../assets/tab-store-icon.svg";

/*
  점주 앱 아래 탭. path 가 null 인 탭은 화면이 아직 없어 누를 수 없다. 화면이 생기면 path 만 채운다.
  아이콘은 피그마에서 받은 색 그대로라(가게 관리만 선택된 노란색) 다른 탭 화면을 만들 때
  그 화면 시안의 선택된 아이콘을 받아 바꾼다.
*/
const TABS = [
  { label: "홈", icon: homeIcon, path: "/" },
  { label: "주문", icon: ordersIcon, path: null },
  { label: "QR 확인", icon: qrIcon, path: null },
  { label: "가게 관리", icon: storeIcon, path: "/owner/store" },
] as const;

const TAB_CLASS = "flex w-16 flex-col items-center gap-1 text-xs leading-[normal] font-medium";

/** 화면 아래에 붙는 탭 바. */
function OwnerTabBar() {
  return (
    <nav className="fixed inset-x-0 bottom-0 flex justify-between border-t border-[#e5e7eb] bg-white px-9 pt-2.5 pb-[max(32px,env(safe-area-inset-bottom))]">
      {TABS.map(({ label, icon, path }) =>
        path ? (
          <NavLink
            key={label}
            to={path}
            end
            className={({ isActive }) => `${TAB_CLASS} ${isActive ? "text-[#b8891a]" : "text-[#9ca3af]"}`}
          >
            <img src={icon} alt="" width={24} height={24} />
            {label}
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
