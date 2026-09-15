import { useState } from 'react';
import { estimateSurveyMinutes, useAgentSurveyTrigger } from './agentSurvey';
import type { UseAgentSurveyTriggerResult } from './agentSurvey';
import { useModal } from './modal';
import { useCurrentUser } from './user';
import type { UseCurrentUserResult } from './user';
import { useSidebarNav } from './sidebarNav';
import type { UseSidebarNavResult } from './sidebarNav';

export type UseStoreListPageResult = UseAgentSurveyTriggerResult &
  UseSidebarNavResult &
  UseCurrentUserResult & {
    /** 조사 시작 모달 열림 여부. */
    isSurveyModalOpen: boolean;
    /** 로봇 버튼 클릭 시 모달을 연다. */
    openSurveyModal: () => void;
    /** 취소 또는 ESC로 모달을 닫는다. */
    closeSurveyModal: () => void;
    /** 모달의 "조사 시작하기". */
    startSurvey: () => void;
    /** 조사 대상 가게 수. 모달의 "가게 수". */
    surveyTargetCount: number;
    /** 예상 소요 시간(분). 모달의 "예상 소요 시간". */
    estimatedMinutes: number;
    /** 선택된 가게 id 집합 */
    selectedIds: Set<string>;
    isSelected: (id: string) => boolean;
    toggleSelect: (id: string, checked: boolean) => void;
    toggleSelectAll: (ids: string[], checked: boolean) => void;
  };

/**
 * 가게 목록 페이지(MockupDataPage)가 쓰는 값 전부를 모은다.
 * 이 훅이 직접 들고 있는 상태는 선택된 가게 id뿐이고,
 * 나머지는 useAgentSurveyTrigger·useModal·useSidebarNav·useCurrentUser를 합성해 내려준다.
 */
export function useStoreListPage(): UseStoreListPageResult {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());

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
  const surveyModal = useModal();
  const sidebarNav = useSidebarNav();
  const currentUser = useCurrentUser();

  /*
    모달을 열 때 말풍선도 함께 닫는다.
    말풍선은 "조사해보라"는 안내인데, 사용자가 로봇 버튼을 눌러 이미 그 행동을 했으므로
    역할이 끝났다. 남겨두면 모달을 닫은 뒤 같은 안내가 다시 보인다.
  */
  const openSurveyModal = () => {
    agentSurvey.dismissTooltip();
    surveyModal.open();
  };

  /*
    조사 시작. 조사 시작 API가 아직 없어 지금은 모달만 닫는다.
    TODO: API가 나오면 여기서 호출한 뒤 결과에 따라 모달을 닫는다.
  */
  const startSurvey = () => {
    surveyModal.close();
  };

  return {
    ...agentSurvey,
    ...sidebarNav,
    ...currentUser,
    isSurveyModalOpen: surveyModal.isOpen,
    openSurveyModal,
    closeSurveyModal: surveyModal.close,
    startSurvey,
    surveyTargetCount: selectedIds.size,
    estimatedMinutes: estimateSurveyMinutes(selectedIds.size),
    selectedIds,
    isSelected,
    toggleSelect,
    toggleSelectAll,
  };
}
