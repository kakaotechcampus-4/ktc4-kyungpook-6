import { useState } from 'react';
import type { SidebarNavKey } from '../components/sidebar/Sidebar';
import { useAgentSurveyTrigger } from './agentSurvey';
import type { UseAgentSurveyTriggerResult } from './agentSurvey';

export type UseStoreListPageResult = UseAgentSurveyTriggerResult & {
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
 * 조사 트리거 상태는 useAgentSurveyTrigger를 합성해 함께 내려준다.
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

  /* 트리거 노출은 현재 페이지가 아니라 전체 선택 수로 판단한다. */
  const agentSurvey = useAgentSurveyTrigger(selectedIds.size);

  return {
    ...agentSurvey,
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
