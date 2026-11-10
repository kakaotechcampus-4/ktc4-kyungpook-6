import { Icon } from '@iconify/react';
import { Fragment, useEffect, useId, useRef, useState } from 'react';
import type { StatusDetail } from '../ui/StatusChip';

/**
 * 전화를 건 뒤 "전화하기" 자리에 찍는 단계. Figma Link btn(전화 대기중 · 전화 중 · 전화 완료 · 전화 실패)
 *
 * 테두리(#E5E7EB)·크기는 "전화하기" 와 같고 바탕·글자색만 단계마다 다르다.
 */
const PHASE = {
  waiting: { label: '전화 대기중', color: 'bg-[#f1f5f9] text-[#475569]', outline: 'focus-visible:outline-[#475569]' },
  calling: { label: '전화 중', color: 'bg-[#eff6ff] text-[#3b82f6]', outline: 'focus-visible:outline-[#3b82f6]' },
  done: { label: '전화 완료', color: 'bg-[#f0fdf4] text-[#15803d]', outline: 'focus-visible:outline-[#15803d]' },
  failed: { label: '전화 실패', color: 'bg-[#fef2f2] text-[#dc2626]', outline: 'focus-visible:outline-[#dc2626]' },
} as const;

export type CallPhase = keyof typeof PHASE;

type CallPhaseButtonProps = {
  phase: CallPhase;
  /** 누르면 아래에 펼치는 세부(들은 답·할 일·사유 코드). */
  details: StatusDetail[];
  /** 판 오른쪽 위에 작은 글자로 곁들이는 한 줄. 예: "2026. 11. 10. 14:02 요청" */
  footnote?: string;
  /** 통화 내용(전사). 있으면 세부 아래 "통화 내용" 으로 붙는다. 전화 중이면 지금까지 오간 말이다. */
  transcript?: { speaker: 'agent' | 'store'; text: string }[];
  className?: string;
};

/**
 * 전화 단계 버튼. 누르면 바로 아래에 세부를 펼치고, 다시 누르거나 바깥을 누르거나 Esc 로 닫는다.
 *
 * 예전에는 단계를 칩으로, 세부는 칩을 눌러 펼쳤고 버튼은 따로 있었다. 같은 단계가 칩과 버튼 두 곳에
 * 찍혀 겹쳐 보여서 버튼 하나로 합쳤다.
 */
function CallPhaseButton({
  phase,
  details,
  footnote,
  transcript = [],
  className,
}: CallPhaseButtonProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const { label, color, outline } = PHASE[phase];
  const transcriptRef = useRef<HTMLOListElement>(null);

  useEffect(() => {
    if (!open) return undefined;

    const closeOnOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('pointerdown', closeOnOutside);
    document.addEventListener('keydown', closeOnEscape);
    /*
      마지막 줄 카드면 판이 화면 아래로 떨어진다(main 이 overflow-auto). 판이 열렸다는 것을
      모르면 스크롤하지 않으므로 끌어온다.
    */
    panelRef.current?.scrollIntoView({ block: 'nearest' });
    /* 전사는 마지막 말이 가장 궁금하다 — 열 때 맨 아래(가장 최근)로 내린다. */
    if (transcriptRef.current) transcriptRef.current.scrollTop = transcriptRef.current.scrollHeight;

    return () => {
      document.removeEventListener('pointerdown', closeOnOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div ref={rootRef} className={['relative w-full', className].filter(Boolean).join(' ')}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        className={`flex h-10 w-full items-center justify-center gap-1 rounded-lg border border-solid border-[#e5e7eb] p-2.5 font-sans text-sm font-semibold leading-5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 ${color} ${outline}`}
      >
        {label}
        <Icon
          icon="material-symbols:keyboard-arrow-down-rounded"
          className={`size-4 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open ? (
        <div ref={panelRef} id={panelId} className="absolute inset-x-0 top-full z-30 pt-1">
          {/*
            판 폭은 버튼 폭과 같다(양 끝을 버튼에 맞춘다). 내용에 따라 폭이 들쭉날쭉하면 카드마다 판 모양이 달라진다.

            평평하게 둔다 — 그림자 대신 얇은 테두리로 판을 가르고, 칸마다 선을 긋지 않는다.
            겹쳐 뜨는 판이라 아래 카드와 섞이지 않을 만큼만 아주 옅은 그림자를 남긴다.
          */}
          <div className="relative rounded-lg border border-solid border-[#e2e8f0] bg-white px-3.5 py-3 shadow-[0px_1px_2px_rgba(15,23,42,0.06)]">
            {/*
              요청 시각은 오른쪽 위 구석에 얹는다. 자리를 비켜 주는 것은 **첫 줄만**이다(첫 값의 pr-36) —
              시각이 세로 한 열을 통째로 차지하면 아래 줄(할 일 등)까지 그 왼쪽에서 꺾였다.
              세부가 없으면(대기·통화 중) 시각 한 줄만 오른쪽에 남는다.
            */}
            {footnote ? (
              <p
                className={`whitespace-nowrap font-sans text-xs leading-4 text-[#94a3b8] ${
                  details.length > 0 ? 'absolute right-3.5 top-3.5' : 'text-right'
                }`}
              >
                {footnote}
              </p>
            ) : null}

            {details.length > 0 ? (
              /* 라벨 열은 가장 긴 라벨에 맞춘다(grid auto). 값이 판 폭을 넘으면 그 칸 안에서 꺾인다. */
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 font-sans text-sm leading-5">
                {details.map((detail, index) => (
                  <Fragment key={`${detail.label}-${detail.value}`}>
                    <dt className="whitespace-nowrap text-[#64748b]">{detail.label}</dt>
                    <dd
                      className={`flex min-w-0 flex-wrap items-center gap-1 break-keep font-medium text-[#1e293b] ${
                        index === 0 && footnote ? 'pr-36' : ''
                      }`}
                    >
                      {detail.value}
                      {detail.tag ? (
                        <span className="inline-flex rounded bg-[#dcfce7] px-1 text-xs font-semibold leading-4 text-[#15803d]">
                          {detail.tag}
                        </span>
                      ) : null}
                    </dd>
                  </Fragment>
                ))}
              </dl>
            ) : null}

            {transcript.length > 0 ? (
              /*
                통화 내용. 세부(무엇을 들었나) 아래에 원문(어떻게 말했나)을 둔다 — 담당자가 답을 의심할 때
                확인하는 자리다. 길면 판 안에서 스크롤한다(판이 화면을 덮지 않게 높이를 묶는다).
              */
              <section aria-label="통화 내용" className="mt-3 border-t border-solid border-[#f1f5f9] pt-3">
                <p className="mb-2 font-sans text-xs font-medium leading-4 text-[#64748b]">
                  {/* 통화 중인 것은 버튼("전화 중")과 맨 아래 … 가 이미 보인다 — 제목에 또 적지 않는다. */}
                  통화 내용
                </p>
                <ol
                  ref={transcriptRef}
                  className="flex max-h-48 flex-col gap-1.5 overflow-y-auto font-sans text-sm leading-5"
                >
                  {transcript.map((line, index) => (
                    <li key={index} className="grid grid-cols-[2.25rem_1fr] gap-2">
                      <span
                        className={`font-medium ${line.speaker === 'agent' ? 'text-[#94a3b8]' : 'text-[#3b82f6]'}`}
                      >
                        {line.speaker === 'agent' ? 'AI' : '가게'}
                      </span>
                      <span className={`break-keep ${line.speaker === 'agent' ? 'text-[#64748b]' : 'text-[#1e293b]'}`}>
                        {line.text}
                      </span>
                    </li>
                  ))}
                  {phase === 'calling' ? (
                    <li className="grid grid-cols-[2.25rem_1fr] gap-2 text-[#94a3b8]">
                      <span />
                      <span className="animate-pulse">…</span>
                    </li>
                  ) : null}
                </ol>
              </section>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

export default CallPhaseButton;
