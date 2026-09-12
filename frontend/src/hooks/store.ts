import { useState } from 'react';
import type { SidebarNavKey } from '../components/sidebar/Sidebar';

export type UseStoreListPageResult = {
  /** 선택된 가게 id 집합 */
  selectedIds: Set<string>;
  isSelected: (id: string) => boolean;
  toggleSelect: (id: string, checked: boolean) => void;
  toggleSelectAll: (ids: string[], checked: boolean) => void;
  /** 사이드바에서 활성화된 내비 항목 */
  activeNavKey: SidebarNavKey;
  setActiveNavKey: (key: SidebarNavKey) => void;
  /** 로그인 사용자 이름 */
  userName: string | undefined;
  /** 사용자 정보 조회 중 여부. Sidebar의 Skeleton 노출 여부에 쓰인다. */
  isUserLoading: boolean;
};

/**
 * 가게 목록 페이지(MockupDataPage)의 선택·내비게이션·사용자 상태를 관리한다.
 * 로그인 사용자를 조회하는 API가 아직 없어 userName/isUserLoading은 더미 값이다.
 */
export function useStoreListPage(): UseStoreListPageResult {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [activeNavKey, setActiveNavKey] = useState<SidebarNavKey>('stores');

  const isSelected = (id: string) => selectedIds.has(id);

  const toggleSelect = (id: string, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  };

  const toggleSelectAll = (ids: string[], checked: boolean) => {
    setSelectedIds(checked ? new Set(ids) : new Set());
  };

  return {
    selectedIds,
    isSelected,
    toggleSelect,
    toggleSelectAll,
    activeNavKey,
    setActiveNavKey,
    userName: undefined,
    isUserLoading: false,
  };
}
