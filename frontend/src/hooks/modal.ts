import { useState } from 'react';

export type UseModalResult = {
  /** 모달 열림 여부. */
  isOpen: boolean;
  open: () => void;
  close: () => void;
};

/**
 * 모달 하나의 열림 상태만 관리한다.
 * 모달의 내용과 무관하므로 여러 페이지에서 그대로 재사용한다.
 *
 * @example
 * const surveyModal = useModal();
 * <AgentSurveyModal open={surveyModal.isOpen} onCancel={surveyModal.close} />
 */
export function useModal(initialOpen = false): UseModalResult {
  const [isOpen, setIsOpen] = useState(initialOpen);

  return {
    isOpen,
    open: () => setIsOpen(true),
    close: () => setIsOpen(false),
  };
}
