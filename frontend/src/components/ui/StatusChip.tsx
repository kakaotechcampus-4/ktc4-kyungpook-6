import { Icon } from '@iconify/react';
import { Fragment, useEffect, useId, useRef, useState } from 'react';

/** 칩 아래 펼쳐지는 세부 한 줄. 예: { label: '요청 시각', value: '2026. 11. 09. 10:06' } */
export type StatusDetail = {
  label: string;
  value: string;
  /** 값 옆에 붙는 작은 표시. 예: 가게에 이미 반영된 답의 "반영됨" */
  tag?: string;
};

/**
 * 칩 색. 화면에 이미 쓰던 색만 쓴다 — Figma 에 노드가 없어서, 디자인이 나왔을 때 지울 코드를 줄인다.
 * progress 진행 중 · info 알림 · done 끝남 · warning 다시 볼 일 · danger 요청 실패
 */
const TONE = {
  progress: 'border-[#e2e8f0] bg-[#f8fafc] text-[#475569]',
  info: 'border-[#bfdbfe] bg-[#eff6ff] text-[#1d4ed8]',
  done: 'border-[#bfdbfe] bg-[#eff6ff] text-[#1d4ed8]',
  warning: 'border-[#fde68a] bg-[#fffbeb] text-[#b45309]',
  danger: 'border-[#fecaca] bg-[#fef2f2] text-[#b91c1c]',
} as const;

export type StatusTone = keyof typeof TONE;

type StatusChipProps = {
  tone: StatusTone;
  /** iconify 이름. 예: material-symbols:call */
  icon: string;
  /** 늘 보이는 한 줄. 짧게 — 자세한 건 details 로 넘긴다. */
  label: string;
  /** 마우스를 올리거나 누르면 펼쳐지는 세부. 비어 있으면 펼칠 것이 없어 버튼이 아닌 글자로 그린다. */
  details?: StatusDetail[];
  /** 진행 중이면 아이콘을 깜빡여 "아직 기다리는 중" 을 보인다. */
  pulse?: boolean;
  /**
   * 바깥에 알리는 방식. 서버가 거절한 것처럼 **끼어들어야 하는** 알림은 'alert' 로 준다.
   *
   * 바깥에서 role="alert" 를 두른 칸으로 감싸는 것으로는 안 된다 — 글자를 담은 칸이 이것이라
   * 안쪽의 'status' 가 이긴다. display:contents 로 감싸면 role 자체가 떨어지기도 한다.
   */
  role?: 'status' | 'alert';
  className?: string;
};

/**
 * 상태 칩. 한 줄 요약만 늘 보이고, 세부는 마우스를 올리거나(포커스 포함) 누르면 펼친다.
 *
 * 누르면 고정된다 — 마우스를 올려 읽다가 세부의 값을 복사하려고 움직이면 닫히는 일을 막는다.
 * 바깥을 누르거나 Esc 로 닫는다.
 *
 * 펼친 판은 칩 바로 아래 겹쳐 뜬다. 판과 칩 사이를 마우스가 지날 때 닫히지 않도록
 * 간격을 margin 이 아니라 판 바깥의 투명한 padding(pt-1)으로 둔다.
 */
function StatusChip({
  tone,
  icon,
  label,
  details = [],
  pulse = false,
  role = 'status',
  className,
}: StatusChipProps) {
  const [hovered, setHovered] = useState(false);
  /**
   * 눌러서 정한 상태. null 이면 마우스·포커스를 따른다.
   *
   * 호버와 불리언 하나로 묶으면 **접기가 먹지 않는다** — 누르려면 먼저 마우스를 올리거나
   * 포커스해야 해서 hovered 가 이미 true 이고, `hovered || pinned` 는 무엇을 넣어도 참이다.
   */
  const [pinned, setPinned] = useState<boolean | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();

  const hasDetails = details.length > 0;
  const open = hasDetails && (pinned ?? hovered);

  /** 마우스·포커스가 빠지면 눌러서 닫아 둔 것은 푼다 — 다시 올리면 또 열려야 한다. */
  const release = () => {
    setHovered(false);
    setPinned((value) => (value === false ? null : value));
  };

  useEffect(() => {
    if (!open) return undefined;

    const closeOnOutside = (event: PointerEvent) => {
      if (rootRef.current?.contains(event.target as Node)) return;
      setPinned(null);
      setHovered(false);
    };
    /* 눌러서 연 것뿐 아니라 포커스로 연 것도 Esc 로 닫는다 — 키보드로는 그 길밖에 없다. */
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPinned(false);
    };

    document.addEventListener('pointerdown', closeOnOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  /*
    눌러서 연 판이 화면 밖이면 끌어온다. main 에 overflow-auto 가 걸려 있어 마지막 줄 카드의 판은
    접힌 자리 아래로 떨어진다 — 스크롤하면 읽히지만, 판이 열렸다는 것을 모르면 스크롤하지 않는다.

    호버로 열 때는 하지 않는다. 마우스를 올리기만 했는데 화면이 움직이고, 그 바람에 칩이
    커서 밑에서 빠져나가 도로 닫힌다.
  */
  useEffect(() => {
    if (pinned !== true) return;
    panelRef.current?.scrollIntoView({ block: 'nearest' });
  }, [pinned]);

  const chipClass = `inline-flex h-7 max-w-full items-center gap-1 rounded-full border border-solid px-2.5 font-sans text-xs font-semibold leading-4 ${TONE[tone]}`;
  const content = (
    <>
      <Icon
        icon={icon}
        className={`size-3.5 shrink-0 ${pulse ? 'animate-pulse' : ''}`}
      />
      {/* 길면 말줄임한다. 잘린 글자는 title 로 다 읽을 수 있다. */}
      <span className="truncate" title={label}>
        {label}
      </span>
    </>
  );

  return (
    <div
      ref={rootRef}
      role={role}
      className={['relative inline-flex max-w-full', className]
        .filter(Boolean)
        .join(' ')}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={release}
    >
      {hasDetails ? (
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setPinned((value) => !(value ?? hovered))}
          onFocus={() => setHovered(true)}
          onBlur={release}
          className={`${chipClass} cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current`}
        >
          {content}
          <Icon
            icon="material-symbols:keyboard-arrow-down-rounded"
            className={`size-4 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
          />
        </button>
      ) : (
        <span className={chipClass}>{content}</span>
      )}

      {open ? (
        <div
          id={panelId}
          ref={panelRef}
          className="absolute left-0 top-full z-50 pt-1"
        >
          {/*
            라벨 열은 가장 긴 라벨에 맞춰 폭을 정한다(grid auto). 고정 폭이면 라벨이 길 때 줄이 꺾이고
            짧을 때 값이 멀리 떨어진다.
          */}
          <dl className="grid w-max min-w-48 max-w-80 grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-lg border border-solid border-[#e2e8f0] bg-white p-3 font-sans text-xs leading-4 shadow-[0px_4px_16px_rgba(15,23,42,0.12)]">
            {details.map((detail) => (
              <Fragment key={`${detail.label}-${detail.value}`}>
                <dt className="whitespace-nowrap text-[#64748b]">{detail.label}</dt>
                <dd className="flex min-w-0 flex-wrap items-center gap-1 break-keep text-[#1e293b]">
                  {detail.value}
                  {detail.tag ? (
                    <span className="inline-flex rounded bg-[#dcfce7] px-1 text-[11px] font-semibold leading-4 text-[#15803d]">
                      {detail.tag}
                    </span>
                  ) : null}
                </dd>
              </Fragment>
            ))}
          </dl>
        </div>
      ) : null}
    </div>
  );
}

export default StatusChip;
