import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { estimateSurveyMinutes, useAgentSurveyTrigger } from './agentSurvey';
import type { UseAgentSurveyTriggerResult } from './agentSurvey';
import { useModal } from './modal';
import { startJob } from '../services/analysis';
import { messageOf } from '../utils/apiError';

export type UseStoreListPageResult = UseAgentSurveyTriggerResult & {
  /** 조사 시작 모달 열림 여부. */
  isSurveyModalOpen: boolean;
  /** 로봇 버튼 클릭 시 모달을 연다. */
  openSurveyModal: () => void;
  /** 취소 또는 ESC로 모달을 닫는다. */
  closeSurveyModal: () => void;
  /** 모달의 "조사 시작하기". 조사를 만들고 결과 화면으로 보낸다. */
  startSurvey: () => void;
  /** 조사 시작 요청이 날아가 있는 동안 true. */
  isStartingSurvey: boolean;
  /** 조사를 시작하지 못한 이유. 없으면 undefined. */
  surveyErrorMessage: string | undefined;
  /** 조사 대상 가게 수. 모달의 "가게 수". */
  surveyTargetCount: number;
  /** 예상 소요 시간(분). 모달의 "예상 소요 시간". */
  estimatedMinutes: number;
  /** 선택된 가게 id 집합 */
  selectedIds: Set<string>;
  isSelected: (id: string) => boolean;
  toggleSelect: (id: string, checked: boolean) => void;
  /** 현재 페이지 ids만 선택·해제한다. 다른 페이지 선택은 유지된다. */
  toggleSelectAll: (ids: string[], checked: boolean) => void;
};

/**
 * 선택 집합에서 ids만 넣거나 뺀 새 집합을 돌려준다. ids 밖의 선택은 그대로 둔다.
 *
 * 헤더 체크박스(전체 선택)는 현재 페이지에만 적용된다. 지메일과 같은 동작이다.
 * 다른 페이지에서 고른 가게까지 지우거나 덮어쓰면 안 된다.
 */
export function applySelection(
  prev: Set<string>,
  ids: string[],
  checked: boolean,
): Set<string> {
  const next = new Set(prev);
  ids.forEach((id) => (checked ? next.add(id) : next.delete(id)));
  return next;
}

/**
 * 가게 목록 페이지(MockupDataPage)의 화면 상태를 모은다.
 * 이 훅이 직접 들고 있는 상태는 선택된 가게 id뿐이고,
 * 나머지는 useAgentSurveyTrigger·useModal을 합성해 내려준다.
 *
 * 셋은 선택 수가 트리거 노출을, 트리거가 모달 열림을 좌우하는 식으로 서로 맞물려 있어
 * 한곳에서 조합할 이유가 있다. 반면 사이드바 내비·로그인 사용자처럼
 * 선택과 무관하고 모든 화면에서 같은 레이아웃 관심사는 Sidebar가 직접 부른다.
 */
export function useStoreListPage(): UseStoreListPageResult {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [surveyErrorMessage, setSurveyErrorMessage] = useState<
    string | undefined
  >(undefined);
  const navigate = useNavigate();

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
    setSelectedIds((prev) => applySelection(prev, ids, checked));
  };

  /* 트리거 노출은 현재 페이지가 아니라 전체 선택 수로 판단한다. */
  const agentSurvey = useAgentSurveyTrigger(selectedIds.size);
  const surveyModal = useModal();

  /*
    모달을 열 때 말풍선도 함께 닫는다.
    말풍선은 "조사해보라"는 안내인데, 사용자가 로봇 버튼을 눌러 이미 그 행동을 했으므로
    역할이 끝났다. 남겨두면 모달을 닫은 뒤 같은 안내가 다시 보인다.
  */
  const openSurveyModal = () => {
    agentSurvey.dismissTooltip();
    setSurveyErrorMessage(undefined);
    surveyModal.open();
  };

  /*
    조사 시작. 성공해야 모달을 닫는다 — 실패했는데 닫으면 담당자는 조사가 시작된 줄 안다.
    조사는 한 번에 하나라, 이미 도는 조사가 있으면 백엔드가 409로 거절하고 그 이유를 모달에 띄운다.

    선택 id 는 화면용 문자열이고 목업 행은 "fallback-1" 처럼 숫자가 아니다.
    서버에 없는 가게라 숫자로 바뀌지 않는 id 는 빼고 보낸다.
  */
  const startMutation = useMutation({
    mutationFn: (storeIds: number[]) => startJob(storeIds),
    onMutate: () => setSurveyErrorMessage(undefined),
    onSuccess: ({ jobId }) => {
      surveyModal.close();
      navigate(`/analysis/${jobId}`);
    },
    onError: (error) => setSurveyErrorMessage(messageOf(error)),
  });

  const startSurvey = () => {
    const storeIds = [...selectedIds]
      .map(Number)
      .filter((id) => Number.isInteger(id) && id > 0);

    if (storeIds.length === 0) {
      setSurveyErrorMessage('조사할 가게를 먼저 고르세요.');
      return;
    }

    startMutation.mutate(storeIds);
  };

  return {
    ...agentSurvey,
    isSurveyModalOpen: surveyModal.isOpen,
    openSurveyModal,
    closeSurveyModal: surveyModal.close,
    startSurvey,
    isStartingSurvey: startMutation.isPending,
    surveyErrorMessage,
    surveyTargetCount: selectedIds.size,
    estimatedMinutes: estimateSurveyMinutes(selectedIds.size),
    selectedIds,
    isSelected,
    toggleSelect,
    toggleSelectAll,
  };
}
