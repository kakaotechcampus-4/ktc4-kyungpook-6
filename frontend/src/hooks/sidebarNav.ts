import { useLocation, useNavigate } from 'react-router-dom';
import type { SidebarNavKey } from '../components/sidebar/Sidebar';

/** 내비 항목이 가리키는 경로. Sidebar의 NAV_ITEMS와 키가 같아야 한다. */
const NAV_ROUTE: Record<SidebarNavKey, string> = {
  stores: '/',
  analysis: '/analysis',
};

/**
 * 경로에서 활성 내비 항목을 구한다.
 * /analysis/:jobId 같은 하위 경로도 같은 항목으로 본다.
 */
function toNavKey(pathname: string): SidebarNavKey {
  return pathname.startsWith(NAV_ROUTE.analysis) ? 'analysis' : 'stores';
}

export type UseSidebarNavResult = {
  /** 현재 경로에 해당하는 내비 항목. */
  activeNavKey: SidebarNavKey;
  /** 내비 항목 클릭. 해당 경로로 이동한다. */
  navigateTo: (key: SidebarNavKey) => void;
};

/**
 * 사이드바 내비게이션 상태. 활성 항목을 useState로 들고 있지 않고
 * 현재 경로에서 파생한다.
 *
 * 페이지마다 activeNavKey를 따로 두면 주소창으로 바로 들어오거나
 * 뒤로 가기를 눌렀을 때 실제 경로와 사이드바 표시가 어긋난다.
 * 경로가 하나뿐인 진실이므로 상태를 따로 두지 않는다.
 */
export function useSidebarNav(): UseSidebarNavResult {
  const { pathname } = useLocation();
  const navigate = useNavigate();

  return {
    activeNavKey: toNavKey(pathname),
    navigateTo: (key) => navigate(NAV_ROUTE[key]),
  };
}
