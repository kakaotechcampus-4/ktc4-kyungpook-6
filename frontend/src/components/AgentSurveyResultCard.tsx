import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import phoneIcon from '../assets/phone.svg';
import Skeleton from './ui/Skeleton';
import CallPhaseButton from './analysis/CallPhaseButton';
import { REQUESTED_AT_LABEL } from '../hooks/analysis';
import type { CallPhase } from './analysis/CallPhaseButton';
import type { StatusDetail } from './ui/StatusChip';

/** 조사로 확인한 근거 한 건. Figma 112:5486 / 112:5480 / 112:5508 */
export type SurveyEvidence = {
  /** 근거 문장. 예: "국세청 사업자등록 상태에서 운영 상태가 휴업으로 확인됨" */
  description: string;
  /**
   * 근거 출처 표기. 예: "근거 링크 - 국세청", "자체 확인일: 120일 전"
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
 * 백엔드 응답(`{ field: "status", value: "CLOSED" }`)을 그대로 받는 타입이 아니라
 * **화면에 찍을 글자**다. 코드 값 → 한글 라벨 변환은 `utils/changeLabel.ts` 가 맡고
 * 훅(useAnalysisResultPage)이 그걸 써서 이 모양으로 넘긴다.
 */
export type SurveyChange = {
  /** 항목 이름. 예: "영업 상태", "가게명" */
  label: string;
  /** 항목 내용. 예: "폐업", "신나는 자장면" */
  description: string;
};

/**
 * 백엔드 Task.classification(우선확인/추가확인/변화없음)을 소문자로 옮긴 값.
 * 실제 enum 문자열이 확정되면 API 응답을 이 값으로 매핑한다.
 */
export type TaskClassification =
  | 'priority'
  | 'additional'
  | 'unchanged'
  | 'failed';

/**
 * 분류별로 달라지는 값. 두 카드의 차이는 이 두 문자열뿐이라 컴포넌트를 나누지 않는다.
 *
 * 라벨을 개별 prop으로 열어두지 않은 이유:
 * "수정 사항"인데 버튼은 "메시지 보내기"인 조합은 화면에 존재하지 않는다.
 * 호출부가 둘을 따로 넘기면 그런 조합이 만들어질 수 있어 한 묶음으로 고정한다.
 *
 * Figma 112:5474(priority) / 112:5502(additional)
 */
const VARIANT = {
  /** 우선확인. 근거가 확실해 proposed_changes를 그대로 반영할 수 있는 건. */
  priority: {
    changesLabel: '수정 사항',
    primaryActionLabel: '즉시 수정 반영하기',
    hasCall: true,
  },
  /**
   * 추가확인. 값을 정할 근거가 부족해 가게에 연락부터 해야 하는 건.
   *
   * 주 버튼이 없다. 원래 "메시지 보내기" 였는데 보낼 길이 없어 연결되지 않은 채
   * 비활성으로 떠 있었고, 이 분류에서 할 일은 가게에 연락하는 것 하나다 —
   * 그 연락은 아래 "전화하기" 가 맡는다(PROMPT-165).
   */
  additional: {
    changesLabel: '추가 확인 사항',
    primaryActionLabel: null,
    hasCall: true,
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
    hasCall: false,
  },
  /*
    조사 실패. 변화가 없는 것과 **조사를 못 한 것은 다르다** — 전자는 넘어가도 되지만
    후자는 아직 아무도 들여다보지 않은 가게다. 한 섹션에 묶으면 그 차이가 화면에서 사라진다.
    바꿀 값이 없으니 주 버튼은 없고, 담당자가 직접 보도록 "직접 수정하기"만 남긴다.
  */
  failed: {
    changesLabel: null,
    primaryActionLabel: null,
    hasCall: false,
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

/** 흰 바탕 보조 버튼(직접 수정하기·전화하기). Figma 791:254 / 791:256 */
const SECONDARY_BUTTON =
  'border border-solid border-[#e5e7eb] bg-white text-[#1f2328] focus-visible:outline-[#64748b] disabled:opacity-60';

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
  /**
   * 가게 전화번호. 주소 아래에 찍는다.
   * issue 가 missing 이면 흐리게("전화번호 없음"), invalid 면 번호 옆에 "걸 수 없는 번호" 를 붙인다.
   */
  phone?: { value: string; issue?: 'missing' | 'invalid' };
  /** 조사가 찾은 근거 목록. */
  evidences?: SurveyEvidence[];
  /** 조사가 제안하는 수정 사항 / 추가 확인 사항 목록. */
  changes?: SurveyChange[];
  /**
   * 조사 실패 이유. 있으면 근거 자리에 이 문장을 대신 적는다.
   * 근거가 비어 있는 것만으로는 "변화가 없다" 와 구분되지 않는다.
   */
  failureReason?: string;
  /**
   * 담당자가 이미 처리한 카드면 그 기록 글자(예: "확인 완료 · 정보수정 · 2026. 10. 13. 20:12").
   * 있으면 주 버튼을 내린다 — 백엔드가 같은 카드를 두 번 처리하지 못하게 409 로 막으므로,
   * 버튼을 남겨 두면 눌러도 오류만 난다.
   */
  verifiedLabel?: string;
  /**
   * Task.classification. 두 번째 섹션 라벨과 주 버튼 라벨이 이 값으로 정해진다.
   * priority = 수정 사항 / 즉시 수정 반영하기, additional = 추가 확인 사항 / 주 버튼 없음,
   * unchanged = 둘 다 없음(임시)
   */
  variant?: TaskClassification;
  /** 주 버튼 클릭. variant에 따라 수정 반영일 수도, 메시지 발송일 수도 있다. */
  onPrimaryAction?: () => void;
  /**
   * 이 카드로 건 확인 전화 한 줄. 아직 걸지 않았으면 undefined 다.
   *
   * 담당자 확인 기록(verifiedLabel)과 따로 둔다 — 전화는 점주에게 물어본 결과이고,
   * 확인 기록은 그 답을 읽은 담당자의 판단이다. 전화가 끝나도 카드는 아직 처리 전이다.
   */
  callStatus?: {
    callId: number;
    /** 전화 버튼 자리에 찍을 단계. 없으면(아직 안 걸었으면) "전화하기" 버튼이다. */
    phase: CallPhase;
    /** 단계 버튼을 누르면 그 아래에 펼치는 세부. */
    details: StatusDetail[];
  };
  /**
   * "전화하기" 클릭. **걸 수 없는 카드면 넘기지 않는다** — 그러면 버튼이 비활성이 된다.
   * 전화는 되돌릴 수 없어서, 눌러도 409 가 날 버튼을 열어 두지 않는다.
   * 버튼 자체는 우선확인·추가확인 카드에만 있다(VARIANT.hasCall).
   */
  onCall?: () => void;
  /** "직접 수정하기" 클릭. 담당자가 값을 직접 고치는 화면으로 보낸다. */
  onEdit?: () => void;
  /** 조사 결과 조회 중일 때만 true. Skeleton은 이 값으로만 노출한다. */
  isLoading?: boolean;
  /**
   * 이 카드의 반영·확인 요청이 날아가 있는 동안 true. 버튼만 잠그고 내용은 그대로 둔다.
   * isLoading 으로 묶으면 카드가 통째로 Skeleton 이 돼, 담당자가 방금 읽던 근거가 사라진다.
   */
  isProcessing?: boolean;
} & Omit<ComponentPropsWithoutRef<'article'>, 'children'>;

/**
 * 에이전트 조사 결과 카드. 가게 1곳에 대해 무엇을 근거로
 * 어떤 조치가 필요한지 보여주고, 처리 방법을 고르게 한다.
 * Figma 112:5474(수정 사항·근거 링크) / 112:5502(추가 확인 사항·링크 없음)
 *
 * 전화하기 버튼은 Figma 791:127 그대로다 — 우선확인·추가확인 카드에서 "직접 수정하기" 와 한 줄에
 * 반씩 놓는다. 전화를 건 뒤에는 그 자리가 단계 버튼(전화 대기중·전화 중·전화 완료·전화 실패)이 되고,
 * 누르면 바로 아래에 세부(요청 시각·들은 답·할 일)를 펼친다(CallPhaseButton).
 *
 * 근거와 수정 사항을 한 흐름(gap 8px)으로 이어 붙인 것은 Figma 112:5478 그대로다.
 * 담당자가 "왜 바꾸는지"를 읽은 직후에 "무엇이 바뀌는지"를 보게 하려는 배치라,
 * 두 섹션을 따로 떼어 간격을 벌리지 않는다.
 */
function AgentSurveyResultCard({
  storeName,
  address,
  phone,
  evidences = [],
  changes = [],
  failureReason,
  verifiedLabel,
  callStatus,
  variant = 'priority',
  onPrimaryAction,
  onCall,
  onEdit,
  isLoading = false,
  isProcessing = false,
  className,
  ...props
}: AgentSurveyResultCardProps) {
  const { changesLabel, primaryActionLabel, hasCall } = VARIANT[variant];
  const showCall = hasCall && !verifiedLabel;

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

  /**
   * 전화 버튼 자리. 아직 안 걸었으면 "전화하기", 걸었으면 그 단계 버튼을 찍는다.
   * 번호가 없거나 걸 수 없는 모양이면 onCall 이 없어서 "전화하기" 가 비활성이 된다.
   * 실패한 전화를 다시 거는 길은 지금 화면에 두지 않는다(백엔드는 허용한다 — 담당자가 정할 일이라
   * 디자인이 자리를 정하면 붙인다).
   */
  const renderCallButton = () => {
    const disabled = isLoading || isProcessing || !onCall;

    if (callStatus) {
      /* 요청 시각은 칸에서 빼서 판 맨 아래 작은 글자로 내린다. */
      const requestedAt = callStatus.details.find((detail) => detail.label === REQUESTED_AT_LABEL);
      return (
        <CallPhaseButton
          callId={callStatus.callId}
          phase={callStatus.phase}
          details={callStatus.details.filter((detail) => detail.label !== REQUESTED_AT_LABEL)}
          footnote={requestedAt ? `${requestedAt.value} 요청` : undefined}
        />
      );
    }

    return (
      <button
        type="button"
        onClick={onCall}
        disabled={disabled}
        className={`${ACTION_BUTTON} ${SECONDARY_BUTTON}`}
      >
        <img src={phoneIcon} alt="" width={22} height={22} className="shrink-0" />
        전화하기
      </button>
    );
  };

  /*
    카드에 overflow-clip 을 두지 않는다 — 전화 상태 칩의 세부가 카드 밖으로 펼쳐질 때 잘린다.
    모서리 둥글림은 border 가 그리므로 잘라 낼 내용이 없다.
  */
  return (
    <article
      className={[
        'flex w-full flex-col items-start gap-3 rounded-lg border border-solid border-[#e2e8f0] bg-white p-3',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {/* 상호명 + 주소·전화번호. Figma 112:5475 (전화번호 줄은 Figma 노드가 없어 주소와 같은 글꼴로 덧붙였다) */}
      <div className="flex w-full items-start justify-between gap-2">
        {/* Figma는 둘 다 한 줄(whitespace-nowrap)이라, 길어지면 줄바꿈 대신 말줄임한다. */}
        <h3 className="min-w-0 flex-1 truncate font-sans text-base font-semibold leading-6 text-black">
          {isLoading ? <Skeleton className="h-6 w-24" /> : storeName}
        </h3>
        <div className="flex max-w-[60%] shrink-0 flex-col items-end gap-0.5">
          <p className="max-w-full truncate font-sans text-sm font-normal leading-5 text-[#1e293b]">
            {isLoading ? <Skeleton className="h-5 w-40" /> : address}
          </p>
          {isLoading ? (
            <Skeleton className="h-5 w-28" />
          ) : phone ? (
            <p
              className={`flex max-w-full items-center gap-1 font-sans text-sm font-normal leading-5 ${
                phone.issue === 'missing' ? 'text-[#94a3b8]' : 'text-[#1e293b]'
              }`}
            >
              <span className="truncate">{phone.value}</span>
              {phone.issue === 'invalid' ? (
                <span className="shrink-0 font-medium text-[#b45309]">· 걸 수 없는 번호</span>
              ) : null}
            </p>
          ) : null}
        </div>
      </div>

      {/* 근거 + 수정 사항. Figma 112:5478 */}
      <div className="flex w-full flex-col items-start gap-2">
        <p className={SECTION_LABEL}>{failureReason ? '조사 결과' : '근거'}</p>

        {failureReason ? (
          <p className="font-sans text-sm font-normal leading-5 text-[#b45309]">
            조사하지 못했습니다 — {failureReason}
          </p>
        ) : (
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
        )}

        {/* 변화없음(unchanged)은 두 번째 섹션이 없다. */}
        {changesLabel ? (
          <>
            <p className={SECTION_LABEL}>{changesLabel}</p>

            {/*
              항목이 없으면 빈 <ul> 을 그리지 않는다 — 비어도 gap 만큼 자리를 먹어, 라벨 아래가
              한 칸 벌어졌다(추가확인 카드에서 라벨과 전화 상태 칩 사이가 떠 보이던 원인).
            */}
            {isLoading || changes.length > 0 ? (
              <ul className="flex w-full flex-col gap-2">
                {isLoading
                  ? SKELETON_ROWS.map((row) => (
                      <li key={row} className={LIST_ITEM}>
                        <Skeleton className="h-5 w-56 max-w-full" />
                      </li>
                    ))
                  : changes.map((change) => (
                      <li key={change.label} className={LIST_ITEM}>
                        <span className="font-medium">{change.label}</span>:{' '}
                        {change.description}
                      </li>
                    ))}
              </ul>
            ) : null}
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
        {verifiedLabel ? (
          <p
            role="status"
            className="flex w-full items-center gap-1 font-sans text-sm font-semibold leading-5 text-[#15803d]"
          >
            <Icon icon="material-symbols:check" className="size-4 shrink-0" />
            {verifiedLabel}
          </p>
        ) : null}
        {/*
          onPrimaryAction 이 없으면 눌러도 아무 일이 없다. 색까지 그대로면 먹통처럼 보이므로
          비활성으로 둔다.
        */}
        {primaryActionLabel && !verifiedLabel ? (
          <button
            type="button"
            onClick={onPrimaryAction}
            disabled={isLoading || isProcessing || !onPrimaryAction}
            className={`${ACTION_BUTTON} bg-[#eab308] text-white focus-visible:outline-[#eab308] disabled:opacity-60`}
          >
            {primaryActionLabel}
          </button>
        ) : null}
        {/*
          직접 수정하기 + 전화하기. Figma 791:253 — 전화가 있는 카드(우선확인·추가확인)는 두 버튼을
          한 줄에 반씩 놓는다. 전화가 없는 카드(변화없음·조사 실패)와 담당자가 이미 처리한 카드는
          직접 수정하기만 한 줄을 다 쓴다 — 답이 나온 뒤에 가게로 전화가 가면 민폐다(백엔드도 409).
        */}
        <div
          className={`grid w-full gap-2 ${showCall ? 'grid-cols-2' : 'grid-cols-1'}`}
        >
          {/*
            조회 중에는 누를 수 없게 막는다.
            어느 가게인지 아직 모르는 자리라 핸들러가 없어서, 열어두면 눌러도
            아무 일이 일어나지 않아 먹통처럼 보인다.
          */}
          <button
            type="button"
            onClick={onEdit}
            disabled={isLoading || isProcessing}
            className={`${ACTION_BUTTON} ${SECONDARY_BUTTON}`}
          >
            직접 수정하기
          </button>
          {/*
            지금 걸 수 없는 카드(걸어 둔 전화가 진행 중·완료)는 onCall 이 없다. 버튼을 숨기면
            카드마다 줄 모양이 달라지므로 자리는 두고 비활성으로 둔다 — 전화는 되돌릴 수 없다.
          */}
          {showCall ? renderCallButton() : null}
        </div>
      </div>
    </article>
  );
}

export default AgentSurveyResultCard;
