import { useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { estimateSurveyMinutes, useAgentSurveyTrigger } from './agentSurvey';
import type { UseAgentSurveyTriggerResult } from './agentSurvey';
import { useModal } from './modal';
import { useSurveyProgressContext } from './surveyProgress';
import { JOB_RESULT_QUERY_PREFIX } from './analysis';
import { startJob } from '../services/analysis';
import type { InvestigationExclusionReason } from '../services/store';
import { messageOf } from '../utils/apiError';
import { exclusionNotices } from '../utils/exclusionLabel';

/**
 * 목록에서 고를 수 있는 가게 한 곳 — 선택이 기억해야 하는 최소 정보.
 *
 * 제외 사유를 선택과 함께 들고 있는 이유: 조사 대상 수는 **페이지와 무관하게** 전체 선택 수로 세는데,
 * 1페이지에서 고른 가게는 3페이지로 넘어가면 화면 데이터에 없다. 고를 때 사유를 같이 적어 두지 않으면
 * 모달이 "이 중 몇 곳이 빠지는지" 를 셀 수 없다.
 */
export type SelectableStore = {
  id: string;
  /** 백엔드가 판정한 조사 제외 사유. 빠지지 않으면 null */
  exclusionReason: InvestigationExclusionReason | null;
};

/** 고른 가게 id → 그 가게의 조사 제외 사유(빠지지 않으면 null). */
export type Selection = ReadonlyMap<string, InvestigationExclusionReason | null>;

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
  /**
   * 고른 가게 중 조사에서 빠지는 곳을 사유별로 알리는 줄. 모달이 "가게 수" 아래에 그린다.
   * **빠지는 가게가 없으면 빈 배열**이라 모달은 줄을 아예 그리지 않는다.
   */
  surveyExclusionNotices: string[];
  /** 예상 소요 시간(분). 모달의 "예상 소요 시간". */
  estimatedMinutes: number;
  /** 선택된 가게 id 집합 */
  selectedIds: Set<string>;
  isSelected: (id: string) => boolean;
  toggleSelect: (store: SelectableStore, checked: boolean) => void;
  /** 현재 페이지 가게만 선택·해제한다. 다른 페이지 선택은 유지된다. */
  toggleSelectAll: (stores: readonly SelectableStore[], checked: boolean) => void;
};

/**
 * 선택에서 넘긴 가게만 넣거나 뺀 새 선택을 돌려준다. 넘기지 않은 가게의 선택은 그대로 둔다.
 *
 * 헤더 체크박스(전체 선택)는 현재 페이지에만 적용된다. 지메일과 같은 동작이다.
 * 다른 페이지에서 고른 가게까지 지우거나 덮어쓰면 안 된다.
 *
 * 넣을 때 제외 사유를 함께 적는다 — 다른 페이지로 넘어가도 모달이 "이 중 몇 곳이 빠지는지" 를 셀 수 있게.
 */
export function applySelection(
  prev: Selection,
  stores: readonly SelectableStore[],
  checked: boolean,
): Map<string, InvestigationExclusionReason | null> {
  const next = new Map(prev);
  stores.forEach((store) =>
    checked ? next.set(store.id, store.exclusionReason) : next.delete(store.id),
  );
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
  const [selected, setSelected] = useState<
    Map<string, InvestigationExclusionReason | null>
  >(() => new Map());
  const [surveyErrorMessage, setSurveyErrorMessage] = useState<
    string | undefined
  >(undefined);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  /* 조사가 돌고 있으면 우하단 자리는 진행 표시가 쓴다. */
  const { progress } = useSurveyProgressContext();

  const isSelected = (id: string) => selected.has(id);

  const toggleSelect = (store: SelectableStore, checked: boolean) => {
    setSelected((prev) => applySelection(prev, [store], checked));
  };

  const toggleSelectAll = (
    stores: readonly SelectableStore[],
    checked: boolean,
  ) => {
    setSelected((prev) => applySelection(prev, stores, checked));
  };

  /* 트리거 노출은 현재 페이지가 아니라 전체 선택 수로 판단한다. */
  const agentSurvey = useAgentSurveyTrigger(selected.size, Boolean(progress));
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
      /*
        우하단 진행 표시가 보는 '가장 최근 조사' 캐시를 바로 갱신한다.
        조사가 없는 동안에는 폴링을 멈춰 두므로(hooks/surveyProgress), 이걸 하지 않으면
        조사를 시작해도 표시가 다음 새로고침까지 뜨지 않는다.
      */
      queryClient.invalidateQueries({ queryKey: JOB_RESULT_QUERY_PREFIX });
      navigate(`/analysis/${jobId}`);
    },
    onError: (error) => setSurveyErrorMessage(messageOf(error)),
  });

  /* 고른 가게의 사유를 세어 모달에 띄울 줄을 만든다. 문구는 utils/exclusionLabel 한 곳에만 있다. */
  const surveyExclusionNotices = useMemo(
    () => exclusionNotices([...selected.values()]),
    [selected],
  );
  /** 화면이 `selectedIds.has(id)` 로 현재 페이지 선택 수를 세므로 id 집합도 함께 내려준다. */
  const selectedIds = useMemo(() => new Set(selected.keys()), [selected]);

  const startSurvey = () => {
    const storeIds = [...selected.keys()]
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
    surveyTargetCount: selected.size,
    surveyExclusionNotices,
    estimatedMinutes: estimateSurveyMinutes(selected.size),
    selectedIds,
    isSelected,
    toggleSelect,
    toggleSelectAll,
  };
}
