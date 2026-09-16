import { useState } from 'react';

/**
 * 가게 1곳을 조사하는 데 걸리는 예상 시간(분).
 * 서버가 예상 시간을 내려주지 않아 Figma 예시(6개 = 10분)에서 역산한 임시값이다.
 */
const ESTIMATED_MINUTES_PER_STORE = 1.7;

/**
 * 가게 수로 조사 예상 소요 시간(분)을 구한다. 모달의 "예상 소요 시간"에 쓴다.
 *
 * 상태가 없는 계산이라 훅이 아니라 순수 함수로 둔다.
 * 서버가 예상 시간을 내려주기 시작하면 이 함수만 걷어내면 된다.
 */
export function estimateSurveyMinutes(storeCount: number): number {
  /* 0분으로 표시되지 않도록 최소 1분으로 올린다. */
  return Math.max(1, Math.round(storeCount * ESTIMATED_MINUTES_PER_STORE));
}

export type UseAgentSurveyTriggerResult = {
  /** 트리거(로봇 버튼) 노출 여부. 선택된 가게가 하나라도 있으면 true. */
  isTriggerVisible: boolean;
  /** 말풍선 노출 여부. 한 번 닫으면 세션 동안 다시 뜨지 않는다. */
  isTooltipVisible: boolean;
  /** 말풍선 닫기(X). */
  dismissTooltip: () => void;
};

/**
 * 가게 조사 트리거(AgentSurveyTrigger)의 노출 여부와 말풍선 상태를 관리한다.
 * 모달 열림 상태는 useModal이, 모달에 들어갈 계산은 estimateSurveyMinutes가 맡는다.
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

  const isTriggerVisible = selectedCount > 0;

  return {
    isTriggerVisible,
    isTooltipVisible: isTriggerVisible && !isTooltipDismissed,
    dismissTooltip: () => setIsTooltipDismissed(true),
  };
}
