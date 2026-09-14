import { useState } from 'react';

export type UseAgentSurveyTriggerResult = {
  /** 트리거(로봇 버튼) 노출 여부. 선택된 가게가 하나라도 있으면 true. */
  isTriggerVisible: boolean;
  /** 말풍선 노출 여부. 한 번 닫으면 세션 동안 다시 뜨지 않는다. */
  isTooltipVisible: boolean;
  /** 말풍선 닫기(X). */
  dismissTooltip: () => void;
  /** 조사 시작 모달 열림 여부. */
  isSurveyDialogOpen: boolean;
  openSurveyDialog: () => void;
  closeSurveyDialog: () => void;
};

/**
 * 가게 조사 트리거(AgentSurveyTrigger)의 노출·말풍선·모달 상태를 관리한다.
 *
 * 말풍선을 닫은 뒤 선택을 모두 해제했다가 다시 선택해도 말풍선은 다시 뜨지 않는다.
 * 이미 안내를 읽은 사용자에게 같은 말풍선을 반복해서 보여줄 이유가 없기 때문이다.
 *
 * @param selectedCount 선택된 가게 수. 페이지를 넘겨도 유지되는 전체 선택 수를 넘긴다.
 */
export function useAgentSurveyTrigger(
  selectedCount: number
): UseAgentSurveyTriggerResult {
  const [isTooltipDismissed, setIsTooltipDismissed] = useState(false);
  const [isSurveyDialogOpen, setIsSurveyDialogOpen] = useState(false);

  const isTriggerVisible = selectedCount > 0;

  return {
    isTriggerVisible,
    isTooltipVisible: isTriggerVisible && !isTooltipDismissed,
    dismissTooltip: () => setIsTooltipDismissed(true),
    isSurveyDialogOpen,
    openSurveyDialog: () => setIsSurveyDialogOpen(true),
    closeSurveyDialog: () => setIsSurveyDialogOpen(false),
  };
}
