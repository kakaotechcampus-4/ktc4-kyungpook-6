import { NavLink } from "react-router-dom";
import homeIcon from "../../assets/tab-home-icon.svg";
import homeActiveIcon from "../../assets/child-tab-home-active-icon.svg";
import qrIcon from "../../assets/tab-qr-icon.svg";
import storesIcon from "../../assets/child-tab-stores-icon.svg";
import storesActiveIcon from "../../assets/child-tab-stores-active-icon.svg";
import meIcon from "../../assets/child-tab-me-icon.svg";

/*
  아동 앱 아래 탭. 점주 탭 바(components/owner/OwnerTabBar.tsx)와 같은 방식이다.
  path 가 null 인 탭은 화면이 아직 없어 누를 수 없다. 화면이 생기면 path 만 채운다.
*/
const TABS: { label: string; icon: string; activeIcon?: string; path: string | null }[] = [
  { label: "홈", icon: homeIcon, activeIcon: homeActiveIcon, path: "/child/home" },
  { label: "가게 목록", icon: storesIcon, activeIcon: storesActiveIcon, path: "/child/stores" },
  { label: "내 QR", icon: qrIcon, path: null },
  { label: "내 정보", icon: meIcon, path: null },
];

const TAB_CLASS = "flex w-16 flex-col items-center gap-1 text-xs leading-[normal] font-medium";

/** 아동 화면 아래에 붙는 탭 바. */
function ChildTabBar() {
  return (
    <nav className="fixed inset-x-0 bottom-0 flex justify-between border-t border-[#e5e7eb] bg-white px-9 pt-2.5 pb-[max(32px,env(safe-area-inset-bottom))]">
      {TABS.map(({ label, icon, activeIcon = icon, path }) =>
        path ? (
          <NavLink
            key={label}
            to={path}
            end
            className={({ isActive }) => `${TAB_CLASS} ${isActive ? "text-[#e3b23c]" : "text-[#9ca3af]"}`}
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

export default ChildTabBar;
