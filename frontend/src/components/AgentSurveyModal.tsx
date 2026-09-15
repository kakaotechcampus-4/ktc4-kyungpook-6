import { Icon } from '@iconify/react';
import { useEffect, useId, useRef } from 'react';
import type { ComponentPropsWithoutRef } from 'react';

/** 조사 예정 정보 목록. Figma 111:3873 */
const SURVEY_TARGETS = [
  '사업상 운영 정보',
  '사업자 등록번호',
  '가게의 위치',
  '가게의 상호명',
  '가게와 관련된 최근 블로그, 리뷰 정보',
] as const;

/** 섹션 라벨("조사 예정 정보", "작업 정보"). Figma 111:3861 / 111:3875 */
const SECTION_LABEL =
  'font-sans text-sm font-semibold leading-5 text-[#475569]';

/** 하단 버튼 공통. Figma 111:3837 / 111:3841 (h 40, w 384, radius 8) */
const ACTION_BUTTON =
  'flex h-10 w-96 shrink-0 items-center justify-center gap-1 overflow-clip rounded-lg p-2.5 font-sans text-sm font-semibold leading-5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2';

type AgentSurveyModalProps = {
  /** 모달 열림 여부. 내부에서 showModal/close를 호출해 이 값과 맞춘다. */
  open: boolean;
  /** 조사 대상 가게 수. "작업 정보"의 "가게 수"에 들어간다. */
  storeCount: number;
  /** 예상 소요 시간(분). */
  estimatedMinutes: number;
  /** "조사 시작하기" 클릭. */
  onStart: () => void;
  /** "취소하기" 클릭 또는 ESC. */
  onCancel: () => void;
} & Omit<ComponentPropsWithoutRef<'dialog'>, 'children' | 'open' | 'onCancel'>;

/**
 * 에이전트 조사 시작 확인 모달. Figma Modal(111:3779) / 111:3780
 *
 * 네이티브 <dialog>의 showModal을 쓴다. 포커스 트랩, ESC 닫기, 배경 딤(::backdrop),
 * 뒤쪽 콘텐츠 비활성화를 브라우저가 처리해주기 때문이다.
 * 배경 클릭으로는 닫지 않는다. <dialog>에서는 카드의 padding 영역 클릭도
 * 배경 클릭과 구분되지 않아 오작동하기 쉽고, Figma에도 취소 버튼이 따로 있다.
 */
function AgentSurveyModal({
  open,
  storeCount,
  estimatedMinutes,
  onStart,
  onCancel,
  className,
  ...props
}: AgentSurveyModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      /*
        ESC로 닫을 때 네이티브 close를 막고 onCancel만 호출한다.
        열림 상태는 open prop이 유일한 기준이어야 하는데, 네이티브가 먼저 닫아버리면
        부모의 상태는 열린 채로 남아 다음 열기가 동작하지 않는다.
      */
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      /*
        m-auto가 가운데 정렬을 만든다.
        브라우저 기본 스타일은 dialog:modal에 `inset: 0` + `margin: auto`를 걸어 중앙에 놓는데,
        Tailwind preflight의 `*, ::backdrop { margin: 0 }`이 그 margin을 덮어써서
        m-auto를 다시 주지 않으면 화면 왼쪽 위에 붙는다.
      */
      className={[
        'm-auto flex-col gap-5 overflow-clip rounded-xl border-0 bg-white p-5 text-[#1e293b] shadow-[0px_4px_16px_4px_rgba(0,0,0,0.25)] backdrop:bg-[rgba(0,0,0,0.33)] open:flex',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {/* 본문. Figma 111:3854 */}
      <div className="flex w-full flex-col items-start gap-3">
        {/* 제목 + 설명. Figma 111:3856 */}
        <div className="flex w-full flex-col items-start gap-1">
          <h2
            id={titleId}
            className="w-80 font-sans text-lg font-semibold leading-7"
          >
            에이전트 조사 시작
          </h2>
          <div
            id={descriptionId}
            className="font-sans text-sm font-normal leading-5"
          >
            <p>선택한 가게의 운영 변동 사항을 에이전트가 확인합니다.</p>
            <p>아래 정보를 웹으로 조사하여 변동사항이 있는지 확인하고 정리합니다.</p>
          </div>
        </div>

        {/* 조사 예정 정보. Figma 111:3874 */}
        <div className="flex flex-col items-start gap-1.5">
          <p className={SECTION_LABEL}>조사 예정 정보</p>
          {/* 왼쪽 3px 회색 바 + Slate/100 배경. Figma 111:3846 */}
          <div className="w-96 border-l-[3px] border-solid border-[#64748b] bg-[#f1f5f9] p-2">
            <ol className="list-decimal ps-[21px] font-sans text-sm font-normal leading-5">
              {SURVEY_TARGETS.map((target) => (
                <li key={target}>{target}</li>
              ))}
            </ol>
          </div>
        </div>

        {/* 작업 정보. Figma 111:3878 */}
        <div className="flex flex-col items-start gap-1.5">
          <p className={SECTION_LABEL}>작업 정보</p>
          <dl className="flex w-96 flex-col items-start gap-1.5 rounded-[9px] bg-[#f1f5f9] px-4 py-3 font-sans text-base leading-6">
            <div className="flex w-full items-start justify-between overflow-clip">
              <dt className="font-normal">가게 수</dt>
              <dd className="font-medium">{storeCount}개</dd>
            </div>
            <div className="flex w-full items-start justify-between overflow-clip">
              <dt className="font-normal">예상 소요 시간</dt>
              <dd className="font-medium">{estimatedMinutes}분</dd>
            </div>
          </dl>
        </div>
      </div>

      {/* 하단 버튼. Figma 111:3836 */}
      <div className="flex flex-col items-start gap-3">
        <button
          type="button"
          onClick={onStart}
          className={`${ACTION_BUTTON} bg-[#3b82f6] text-white focus-visible:outline-[#3b82f6]`}
        >
          <Icon
            icon="mingcute:robot-fill"
            className="size-4 shrink-0 text-white"
          />
          조사 시작하기
        </button>
        <button
          type="button"
          onClick={onCancel}
          className={`${ACTION_BUTTON} border border-solid border-[#e2e8f0] text-[#1e293b] focus-visible:outline-[#64748b]`}
        >
          취소하기
        </button>
      </div>
    </dialog>
  );
}

export default AgentSurveyModal;
