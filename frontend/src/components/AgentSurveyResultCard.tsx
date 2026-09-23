import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import Skeleton from './ui/Skeleton';

/** 조사로 확인한 근거 한 건. Figma 112:5486 / 112:5480 / 112:5508 */
export type SurveyEvidence = {
  /** 근거 문장. 예: "국세청 사업자등록 상태에서 운영 상태가 휴업으로 확인됨" */
  description: string;
  /**
   * 근거 출처 표기. 예: "근거 링크 - 국세청", "자체 확인일: 6개월 전"
   *
   * 외부 출처가 없는 근거(자체 데이터로 판단한 건)도 있어서,
   * sourceUrl 없이 라벨만 오면 링크가 아닌 텍스트로 렌더한다. Figma 112:5511
   */
  sourceLabel?: string;
  /** 근거 링크 URL. 있을 때만 링크(<a> + 링크 아이콘)가 된다. */
  sourceUrl?: string;
};

/**
 * 수정 사항 / 추가 확인 사항 한 줄. Figma 112:5494 / 112:5493 / 112:5513
 *
 * 백엔드 Task.proposed_changes(JSON)를 그대로 받는 타입이 아니라 화면에 찍을 문자열이다.
 * proposed_changes는 { status: "SUSPENDED" } 같은 갱신값일 텐데,
 * "가게 상태: '휴업' 갱신"으로 바꾸려면 필드명·enum의 한글 라벨을 알아야 한다.
 * 그 변환은 용어집(docs/도메인_용어집.md)이 채워진 뒤 호출부에서 맡는다.
 */
export type SurveyChange = {
  /** 항목 이름. 예: "가게 상태", "자체 확인 요망" */
  field: string;
  /** 항목 내용. 예: "'휴업' 갱신", "010-XXXX-XXXX" */
  description: string;
};

/**
 * 백엔드 Task.classification(우선확인/추가확인/변화없음)을 소문자로 옮긴 값.
 * 실제 enum 문자열이 확정되면 API 응답을 이 값으로 매핑한다.
 */
export type TaskClassification = 'priority' | 'additional' | 'unchanged';

/**
 * 분류별로 달라지는 값. 두 카드의 차이는 이 두 문자열뿐이라 컴포넌트를 나누지 않는다.
 *
 * 라벨을 개별 prop으로 열어두지 않은 이유:
 * "수정 사항"인데 버튼은 "메세지 보내기"인 조합은 화면에 존재하지 않는다.
 * 호출부가 둘을 따로 넘기면 그런 조합이 만들어질 수 있어 한 묶음으로 고정한다.
 *
 * Figma 112:5474(priority) / 112:5502(additional)
 */
const VARIANT = {
  /** 우선확인. 근거가 확실해 proposed_changes를 그대로 반영할 수 있는 건. */
  priority: {
    changesLabel: '수정 사항',
    primaryActionLabel: '즉시 수정 반영하기',
  },
  /** 추가확인. 값을 정할 근거가 부족해 가게에 연락부터 해야 하는 건. */
  additional: {
    changesLabel: '추가 확인 사항',
    primaryActionLabel: '메세지 보내기',
  },
  /*
    변화없음. 아직 Figma 노드가 없어 임시로 잡아둔 모양이다.
    바꿀 것도 확인할 것도 없는 건이라 두 번째 섹션과 주 버튼을 빼고,
    "그래도 고치겠다"는 경우를 위해 직접 수정하기만 남긴다.
    새 색이나 뱃지를 만들지 않은 건 디자인이 나왔을 때 지울 코드를 줄이려는 것이다.
  */
  unchanged: {
    changesLabel: null,
    primaryActionLabel: null,
  },
} as const;

/** 섹션 라벨("근거", "수정 사항"/"추가 확인 사항"). Figma 112:5479 / 112:5492 */
const SECTION_LABEL = 'font-sans text-sm font-medium leading-5 text-[#475569]';

/** 근거·수정 사항 목록 항목. 불릿 마커가 ms-[21px] 바깥 여백에 놓인다. */
const LIST_ITEM =
  'ms-[21px] list-disc font-sans text-sm font-normal leading-5 text-[#1e293b]';

/** 근거 출처 텍스트. 링크일 때도 같은 색·굵기다. Figma 112:5491 / 112:5511 */
const EVIDENCE_SOURCE =
  'flex w-fit items-center gap-1 font-sans text-sm font-semibold leading-5 text-[#3b82f6]';

/** 하단 버튼 공통. Figma 112:5496 / 112:5498 (h 40, radius 8) */
const ACTION_BUTTON =
  'flex h-10 w-full shrink-0 items-center justify-center gap-1 overflow-clip rounded-lg p-2.5 font-sans text-sm font-semibold leading-5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2';

/**
 * 조회 중에 보여줄 자리 수. 실제 건수는 응답을 받아야 알 수 있어
 * Figma 예시(근거 2건 / 수정 사항 2건)와 같은 수만큼 자리를 잡아둔다.
 */
const SKELETON_ROWS = [0, 1];

type AgentSurveyResultCardProps = {
  /** 상호명. Figma 112:5476 */
  storeName?: string;
  /** 주소. Figma 112:5477 */
  address?: string;
  /** 조사가 찾은 근거 목록. */
  evidences?: SurveyEvidence[];
  /** 조사가 제안하는 수정 사항 / 추가 확인 사항 목록. */
  changes?: SurveyChange[];
  /**
   * Task.classification. 두 번째 섹션 라벨과 주 버튼 라벨이 이 값으로 정해진다.
   * priority = 수정 사항 / 즉시 수정 반영하기, additional = 추가 확인 사항 / 메세지 보내기,
   * unchanged = 둘 다 없음(임시)
   */
  variant?: TaskClassification;
  /** 주 버튼 클릭. variant에 따라 수정 반영일 수도, 메세지 발송일 수도 있다. */
  onPrimaryAction?: () => void;
  /** "직접 수정하기" 클릭. 담당자가 값을 직접 고치는 화면으로 보낸다. */
  onEdit?: () => void;
  /** 조사 결과 조회 중일 때만 true. Skeleton은 이 값으로만 노출한다. */
  isLoading?: boolean;
} & Omit<ComponentPropsWithoutRef<'article'>, 'children'>;

/**
 * 에이전트 조사 결과 카드. 가게 1곳에 대해 무엇을 근거로
 * 어떤 조치가 필요한지 보여주고, 처리 방법을 고르게 한다.
 * Figma 112:5474(수정 사항·근거 링크) / 112:5502(추가 확인 사항·링크 없음)
 *
 * 근거와 수정 사항을 한 흐름(gap 8px)으로 이어 붙인 것은 Figma 112:5478 그대로다.
 * 담당자가 "왜 바꾸는지"를 읽은 직후에 "무엇이 바뀌는지"를 보게 하려는 배치라,
 * 두 섹션을 따로 떼어 간격을 벌리지 않는다.
 */
function AgentSurveyResultCard({
  storeName,
  address,
  evidences = [],
  changes = [],
  variant = 'priority',
  onPrimaryAction,
  onEdit,
  isLoading = false,
  className,
  ...props
}: AgentSurveyResultCardProps) {
  const { changesLabel, primaryActionLabel } = VARIANT[variant];

  /**
   * 근거 출처. URL이 있으면 링크 아이콘이 붙은 <a>로, 없으면 파란 텍스트로만 낸다.
   * 텍스트일 때의 py-px는 아이콘(24px)이 빠진 만큼 줄 간격을 메운다. Figma 112:5510
   */
  const renderEvidenceSource = ({ sourceLabel, sourceUrl }: SurveyEvidence) => {
    if (!sourceLabel) return null;

    if (!sourceUrl) {
      return <span className={`${EVIDENCE_SOURCE} py-px`}>{sourceLabel}</span>;
    }

    return (
      <a
        href={sourceUrl}
        target="_blank"
        rel="noreferrer noopener"
        className={`${EVIDENCE_SOURCE} focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6]`}
      >
        <Icon icon="material-symbols:link" className="size-6 shrink-0" />
        {sourceLabel}
      </a>
    );
  };

  return (
    <article
      className={[
        'flex w-full flex-col items-start gap-3 overflow-clip rounded-lg border border-solid border-[#e2e8f0] p-3',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {/* 상호명 + 주소. Figma 112:5475 */}
      <div className="flex w-full items-center justify-between gap-2">
        {/* Figma는 둘 다 한 줄(whitespace-nowrap)이라, 길어지면 줄바꿈 대신 말줄임한다. */}
        <h3 className="min-w-0 flex-1 truncate font-sans text-base font-semibold leading-6 text-black">
          {isLoading ? <Skeleton className="h-6 w-24" /> : storeName}
        </h3>
        <p className="max-w-[60%] shrink-0 truncate font-sans text-sm font-normal leading-5 text-[#1e293b]">
          {isLoading ? <Skeleton className="h-5 w-40" /> : address}
        </p>
      </div>

      {/* 근거 + 수정 사항. Figma 112:5478 */}
      <div className="flex w-full flex-col items-start gap-2">
        <p className={SECTION_LABEL}>근거</p>

        <ul className="flex w-full flex-col gap-2">
          {isLoading
            ? SKELETON_ROWS.map((row) => (
                <li key={row} className={LIST_ITEM}>
                  <Skeleton className="h-5 w-72 max-w-full" />
                  {/*
                    출처가 링크인지 텍스트인지는 응답을 받아야 알 수 있다.
                    링크 아이콘 자리를 미리 잡아두면 텍스트 출처일 때 자리가 밀리므로
                    조회 중에는 라벨 자리만 잡는다.
                  */}
                  <span className="block py-px">
                    <Skeleton className="h-5 w-28" />
                  </span>
                </li>
              ))
            : evidences.map((evidence) => (
                <li key={evidence.description} className={LIST_ITEM}>
                  {evidence.description}
                  {renderEvidenceSource(evidence)}
                </li>
              ))}
        </ul>

        {/* 변화없음(unchanged)은 두 번째 섹션이 없다. */}
        {changesLabel ? (
          <>
            <p className={SECTION_LABEL}>{changesLabel}</p>

            <ul className="flex w-full flex-col gap-2">
              {isLoading
                ? SKELETON_ROWS.map((row) => (
                    <li key={row} className={LIST_ITEM}>
                      <Skeleton className="h-5 w-56 max-w-full" />
                    </li>
                  ))
                : changes.map((change) => (
                    <li key={change.field} className={LIST_ITEM}>
                      <span className="font-medium">{change.field}</span>:{' '}
                      {change.description}
                    </li>
                  ))}
            </ul>
          </>
        ) : null}
      </div>

      {/*
        하단 버튼. Figma 112:5495
        mt-auto는 카드가 그리드에서 옆 카드 높이에 맞춰 늘어났을 때 쓰인다.
        늘어난 만큼을 버튼 위 여백으로 흘려보내 버튼을 바닥에 붙인다.
        카드 높이가 내용 그대로일 때는 남는 공간이 없어 아무 영향이 없다.
      */}
      <div className="mt-auto flex w-full flex-col items-start gap-2">
        {primaryActionLabel ? (
          <button
            type="button"
            onClick={onPrimaryAction}
            disabled={isLoading}
            className={`${ACTION_BUTTON} bg-[#eab308] text-white focus-visible:outline-[#eab308] disabled:opacity-60`}
          >
            {primaryActionLabel}
          </button>
        ) : null}
        {/*
          조회 중에는 누를 수 없게 막는다.
          어느 가게인지 아직 모르는 자리라 핸들러가 없어서, 열어두면 눌러도
          아무 일이 일어나지 않아 먹통처럼 보인다.
        */}
        <button
          type="button"
          onClick={onEdit}
          disabled={isLoading}
          className={`${ACTION_BUTTON} border border-solid border-[#e2e8f0] text-[#1e293b] focus-visible:outline-[#64748b] disabled:opacity-60`}
        >
          직접 수정하기
        </button>
      </div>
    </article>
  );
}

export default AgentSurveyResultCard;
