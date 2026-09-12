import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import Skeleton from './ui/Skeleton';

/** 백엔드 StoreStatus(OPEN/SUSPENDED/CLOSED/UNKNOWN)를 소문자로 옮긴 값. */
export type BusinessStatus = 'open' | 'suspended' | 'closed' | 'unknown';

/**
 * 영업 상태별 값. 라벨은 백엔드 StoreStatus 4가지를 1:1로 옮긴다.
 *
 * 4가지를 "확인 필요" 같은 상위 개념으로 합치지 않는 이유:
 * 휴업(확인된 사실)과 미확인(확인 자체가 안 된 상태)은 담당자가 할 일이 서로 다르다.
 * 휴업은 재개 여부를 나중에 다시 보면 되지만, 미확인은 지금 확인해야 하는 건이다.
 * 둘을 한 뱃지로 묶으면 이 차이가 화면에서 사라진다.
 *
 * 대신 "확인 필요"는 상태와 직교하는 축이므로 needsReview 플래그로 따로 둔다.
 * 휴업·폐업은 모두 다시 영업중으로 돌아올 수 있어 경고색 없이 중립 계열 농도로만 구분하고,
 * 미확인만 진한 앰버 + 경고 아이콘으로 목록에서 가장 먼저 눈에 걸리게 한다.
 *
 * Figma 뱃지 노드(111:2316 영업중 / 111:2298 회색 뱃지)
 */
const STATUS_STYLE = {
  open: {
    label: '영업중',
    background: 'bg-[#86efac]',
    dot: 'bg-[#15803d]',
    text: 'text-[#15803d]',
    needsReview: false,
  },
  suspended: {
    label: '휴업',
    background: 'bg-[#e2e8f0]',
    dot: 'bg-[#64748b]',
    text: 'text-[#64748b]',
    needsReview: false,
  },
  closed: {
    label: '폐업',
    background: 'bg-[#cbd5e1]',
    dot: 'bg-[#334155]',
    text: 'text-[#334155]',
    needsReview: false,
  },
  unknown: {
    label: '미확인',
    background: 'bg-[#fcd34d]',
    dot: 'bg-[#92400e]',
    text: 'text-[#92400e]',
    needsReview: true,
  },
} as const;

const CELL_TEXT =
  "w-full font-sans text-sm font-normal leading-5 text-[#1e293b]";

type TableRowProps = {
  /** 상호명 */
  name?: string;
  phone?: string;
  address?: string;
  /** 마지막 확인일 (예: "3주 전") */
  lastCheckedAt?: string;
  status?: BusinessStatus;
  checked?: boolean;
  onCheckedChange?: (checked: boolean) => void;
  onEdit?: () => void;
  /** 행 데이터 조회 중일 때만 true. Skeleton은 이 값으로만 노출한다. */
  isLoading?: boolean;
} & Omit<ComponentPropsWithoutRef<'div'>, 'children'>;

/** 가게 목록 테이블의 한 행. Figma 111:2309(미선택·영업중) / 111:2291(선택·회색 뱃지) */
function TableRow({
  name,
  phone,
  address,
  lastCheckedAt,
  status,
  checked = false,
  onCheckedChange,
  onEdit,
  isLoading = false,
  className,
  ...props
}: TableRowProps) {
  const badge = status ? STATUS_STYLE[status] : undefined;

  /** 조회 중이면 Skeleton, 값이 있으면 텍스트. 값이 없으면 비워둔다. */
  const renderCellText = (value: string | undefined) => {
    if (isLoading) return <Skeleton className="h-5 w-full" />;
    if (!value) return null;
    return <p className={CELL_TEXT}>{value}</p>;
  };

  return (
    <div
      className={[
        'flex w-full items-center gap-2.5 border-b border-solid border-[#e2e8f0] bg-white px-2 py-1.5',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {/* 체크박스 — 정적 컨트롤이므로 조회 중에도 그대로 렌더한다. */}
      <label className="relative shrink-0">
        <input
          type="checkbox"
          checked={checked}
          onChange={(event) => onCheckedChange?.(event.target.checked)}
          className="peer sr-only"
          aria-label={name ? `${name} 선택` : '행 선택'}
        />
        <span
          className={[
            'flex size-5 items-center justify-center overflow-clip rounded-[2px] peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-[#3b82f6]',
            checked
              ? 'bg-[#3b82f6]'
              : 'border border-solid border-[#64748b] bg-white',
          ].join(' ')}
        >
          {checked ? (
            <Icon
              icon="material-symbols:check"
              className="size-4 shrink-0 text-white"
            />
          ) : null}
        </span>
      </label>

      {/* 상호명 — 남는 공간을 모두 차지한다. Figma flex-[1_0_0] + min-w-px */}
      <div className="flex h-5 min-w-px flex-1 flex-col justify-center overflow-clip">
        {renderCellText(name)}
      </div>

      {/* 전화번호 */}
      <div className="flex h-5 w-64 shrink-0 flex-col justify-center overflow-clip">
        {renderCellText(phone)}
      </div>

      {/* 영업 상태 뱃지 */}
      <div className="flex w-32 shrink-0 flex-col items-start">
        {isLoading ? (
          <Skeleton className="h-6 w-[69px]" />
        ) : badge ? (
          <div
            className={[
              'flex shrink-0 items-center gap-1.5 overflow-clip rounded-[22px] px-2.5 py-0.5',
              badge.background,
            ].join(' ')}
            title={badge.needsReview ? '담당자 확인이 필요합니다' : undefined}
            aria-label={
              badge.needsReview ? `${badge.label} — 담당자 확인 필요` : badge.label
            }
          >
            {badge.needsReview ? (
              <Icon
                icon="lucide:alert-circle"
                className={`size-3.5 shrink-0 ${badge.text}`}
              />
            ) : (
              <span className={`size-1.5 shrink-0 rounded-[4px] ${badge.dot}`} />
            )}
            <p
              className={`shrink-0 whitespace-nowrap font-sans text-sm font-semibold leading-5 ${badge.text}`}
            >
              {badge.label}
            </p>
          </div>
        ) : null}
      </div>

      {/* 주소 */}
      <div className="flex h-5 w-64 shrink-0 flex-col justify-center overflow-clip">
        {renderCellText(address)}
      </div>

      {/* 마지막 확인일 */}
      <div className="flex h-5 w-64 shrink-0 flex-col justify-center overflow-clip">
        {renderCellText(lastCheckedAt)}
      </div>

      {/* 수정 버튼 */}
      <button
        type="button"
        onClick={onEdit}
        aria-label={name ? `${name} 수정` : '수정'}
        className="shrink-0 overflow-clip"
      >
        <Icon icon="lucide:edit" className="size-6 text-[#94a3b8]" />
      </button>
    </div>
  );
}

export default TableRow;
