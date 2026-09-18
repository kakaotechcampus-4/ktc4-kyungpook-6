import type { ComponentPropsWithoutRef } from 'react';
import NavItem from './NavItem';
import SidebarTop from './SidebarTop';
import Skeleton from '../ui/Skeleton';
import { useSidebarNav } from '../../hooks/sidebarNav';
import type { SidebarNavKey } from '../../hooks/sidebarNav';
import { useCurrentUser } from '../../hooks/user';

const NAV_ITEMS: {
  key: SidebarNavKey;
  icon: string;
  label: string;
}[] = [
  { key: 'stores', icon: 'mingcute:list-check-fill', label: '가게 목록' },
  { key: 'analysis', icon: 'mingcute:robot-fill', label: '분석 결과' },
];

type SidebarProps = Omit<ComponentPropsWithoutRef<'aside'>, 'children'>;

/**
 * 공통 사이드바. Figma Sidebar(176:59)
 *
 * 활성 내비 항목과 로그인 사용자는 props로 받지 않고 훅으로 직접 알아낸다.
 * 어느 페이지에 놓이든 값이 같은 레이아웃 전역 관심사라,
 * 페이지가 받아서 다시 내려주면 화면이 늘어날 때마다 같은 코드가 복사된다.
 */
function Sidebar({ className, ...props }: SidebarProps) {
  const { activeNavKey, navigateTo } = useSidebarNav();
  const { userName, isUserLoading } = useCurrentUser();

  const initial = userName?.charAt(0);

  return (
    <aside
      className={[
        'flex h-full w-32 flex-col items-start border border-solid border-[#e2e8f0] bg-white',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <SidebarTop />

      <nav className="flex min-h-px w-full flex-1 flex-col items-start gap-1 overflow-clip p-3">
        {NAV_ITEMS.map((item) => (
          <NavItem
            key={item.key}
            icon={item.icon}
            label={item.label}
            active={activeNavKey === item.key}
            onClick={() => navigateTo(item.key)}
          />
        ))}
      </nav>

      {/*
        푸터 프레임과 아바타 원형은 정적 요소이므로 항상 렌더한다.
        Skeleton은 조회 중인 텍스트 노드에만 적용한다. (figma.md 11~12줄)
        비로그인·조회 실패 상태는 대응하는 Figma 노드가 없어 텍스트를 비워둔다.
      */}
      <div className="flex w-full shrink-0 items-center gap-2 overflow-clip border-t border-solid border-[#e2e8f0] p-3">
        <div className="flex size-8 shrink-0 flex-col items-center justify-center overflow-clip rounded-2xl bg-[#fef9c3]">
          {isUserLoading ? (
            <Skeleton className="h-[15px] w-3" />
          ) : initial ? (
            <p className="shrink-0 whitespace-nowrap font-['Inter',sans-serif] text-xs font-bold leading-normal text-[#eab308]">
              {initial}
            </p>
          ) : null}
        </div>
        {isUserLoading ? (
          <Skeleton className="h-4 w-8" />
        ) : userName ? (
          <p className="shrink-0 whitespace-nowrap font-sans text-xs font-medium leading-4 text-[#94a3b8]">
            {userName}
          </p>
        ) : null}
      </div>
    </aside>
  );
}

export default Sidebar;
