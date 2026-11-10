import { NavLink } from "react-router-dom";
import homeIcon from "../../assets/tab-home-icon.svg";
import homeActiveIcon from "../../assets/child-tab-home-active-icon.svg";
import qrIcon from "../../assets/tab-qr-icon.svg";
import qrActiveIcon from "../../assets/child-tab-qr-active-icon.svg";
import storesIcon from "../../assets/child-tab-stores-icon.svg";
import storesActiveIcon from "../../assets/child-tab-stores-active-icon.svg";

/*
  아동 앱 아래 탭. 점주 탭 바(components/owner/OwnerTabBar.tsx)와 같은 방식이다.
  탭마다 선택됐을 때(activeIcon, 노란색)와 아닐 때(icon, 회색) 아이콘을 따로 둔다.
  "내 정보" 탭은 화면이 없어 뺐다(2026-11-11) — 눌리지 않는 탭이 자리만 차지했다.
*/
const TABS: { label: string; icon: string; activeIcon: string; path: string }[] = [
  { label: "홈", icon: homeIcon, activeIcon: homeActiveIcon, path: "/child/home" },
  { label: "가게 목록", icon: storesIcon, activeIcon: storesActiveIcon, path: "/child/stores" },
  { label: "내 QR", icon: qrIcon, activeIcon: qrActiveIcon, path: "/child/qr" },
];

const TAB_CLASS = "flex w-16 flex-col items-center gap-1 text-xs leading-[normal] font-medium";

/** 아동 화면 아래에 붙는 탭 바. */
function ChildTabBar() {
  return (
    /* 탭이 셋이라 양 끝에 붙이지 않고 고르게 벌린다(justify-evenly). */
    <nav className="fixed inset-x-0 bottom-0 flex justify-evenly border-t border-[#e5e7eb] bg-white px-4 pt-2.5 pb-[max(32px,env(safe-area-inset-bottom))]">
      {TABS.map(({ label, icon, activeIcon, path }) => (
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
      ))}
    </nav>
  );
}

export default ChildTabBar;
