import { useState } from 'react';
import { useStoreCandidates } from '../../hooks/ownerApproval';
import type { NameMatch, StoreCandidateResponse } from '../../services/owner';
import Skeleton from '../ui/Skeleton';

/** 신청서 상호명과 가게명이 얼마나 같은지. 용어집의 화면 라벨을 따른다. */
const NAME_MATCH_LABEL: Record<NameMatch, string> = {
  EXACT: '상호명 같음',
  PARTIAL: '상호명 일부 같음',
  NONE: '상호명 다름',
};

/** 국세청 상태 → 화면 라벨. 가게 목록 뱃지와 같은 말을 쓴다. */
const NTS_LABEL: Record<string, string> = {
  ACTIVE: '국세청 영업',
  SUSPENDED: '국세청 휴업',
  CLOSED: '국세청 폐업',
  NOT_REGISTERED: '국세청 미등록',
};

const CHIP = 'rounded-[22px] px-2 py-0.5 text-xs font-semibold leading-4';
const CHIP_ON = `${CHIP} bg-[#86efac] text-[#15803d]`;
const CHIP_OFF = `${CHIP} bg-[#e2e8f0] text-[#64748b]`;
const CHIP_WARN = `${CHIP} bg-[#fcd34d] text-[#92400e]`;

/**
 * 어디까지 일치하는지를 칩으로 늘어놓는다.
 *
 * **일치하지 않는 것도 숨기지 않고 회색으로 보여준다.** "사업자번호 일치" 가 보이지 않는 것과
 * "사업자번호가 다름" 은 담당자에게 다른 정보인데, 맞는 것만 그리면 둘이 구분되지 않는다.
 */
function MatchChips({ candidate }: { candidate: StoreCandidateResponse }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className={candidate.bizNoMatched ? CHIP_ON : CHIP_OFF}>
        사업자번호 {candidate.bizNoMatched ? '같음' : '다름'}
      </span>
      <span className={candidate.phoneMatched ? CHIP_ON : CHIP_OFF}>
        휴대폰 {candidate.phoneMatched ? '같음' : '다름'}
      </span>
      <span className={candidate.nameMatch === 'EXACT' ? CHIP_ON : CHIP_OFF}>
        {NAME_MATCH_LABEL[candidate.nameMatch]}
      </span>
      {candidate.ntsState ? (
        <span className={candidate.ntsState === 'ACTIVE' ? CHIP_OFF : CHIP_WARN}>
          {NTS_LABEL[candidate.ntsState] ?? candidate.ntsState}
        </span>
      ) : null}
      {/* 이미 연결된 점주가 있으면 재가입·양수·사칭 중 무엇인지 확인해야 한다. */}
      {candidate.linkedOwnerCount > 0 ? (
        <span className={CHIP_WARN}>
          이미 연결된 점주 {candidate.linkedOwnerCount}명
        </span>
      ) : null}
    </div>
  );
}

type StoreCandidatePickerProps = {
  memberId: number;
  /** 처리 요청이 날아가 있는 동안 true. 버튼을 잠근다. */
  isProcessing: boolean;
  onApprove: (storeId: number) => void;
};

/**
 * 가입을 승인할 때 연결할 가게를 고르는 자리.
 *
 * 서버가 찾아 준 후보만 보여주고 **확정은 담당자가 합니다.** 신청서의 번호·상호명은 본인이 적은
 * 값이라, 그것만으로 자동 연결하면 남의 가게에 붙을 수 있습니다(`StoreCandidateResponse` 주석).
 */
function StoreCandidatePicker({
  memberId,
  isProcessing,
  onApprove,
}: StoreCandidatePickerProps) {
  const { data: candidates, isPending, isError } = useStoreCandidates(memberId);
  const [selectedStoreId, setSelectedStoreId] = useState<number | null>(null);

  if (isPending) {
    return (
      <div className="flex w-full flex-col gap-2">
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-14 w-full" />
      </div>
    );
  }

  if (isError) {
    return (
      <p role="alert" className="text-sm text-[#b91c1c]">
        후보 가게를 불러오지 못했습니다.
      </p>
    );
  }

  if (!candidates || candidates.length === 0) {
    return (
      <p className="text-sm text-[#64748b]">
        연결할 수 있는 후보 가게가 없습니다. 가게 자료를 먼저 넣거나, 신청서의
        사업자등록번호·상호명이 맞는지 확인해 주세요.
      </p>
    );
  }

  return (
    <div className="flex w-full flex-col items-start gap-2">
      <p className="text-sm font-medium leading-5 text-[#475569]">
        연결할 가게 고르기 ({candidates.length}곳)
      </p>

      <ul className="flex w-full flex-col gap-2">
        {candidates.map((candidate) => (
          <li key={candidate.storeId}>
            <label className="flex w-full cursor-pointer items-start gap-2 rounded-lg border border-solid border-[#e2e8f0] p-2.5 has-[:checked]:border-[#3b82f6] has-[:checked]:bg-[#eff6ff]">
              <input
                type="radio"
                name={`store-candidate-${memberId}`}
                className="mt-1 shrink-0"
                checked={selectedStoreId === candidate.storeId}
                onChange={() => setSelectedStoreId(candidate.storeId)}
              />
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-sans text-sm font-semibold leading-5 text-[#0f172a]">
                    {candidate.name}
                  </span>
                  <span className="truncate font-sans text-xs leading-4 text-[#64748b]">
                    {candidate.addressRoad ?? '주소 없음'}
                  </span>
                </span>
                <MatchChips candidate={candidate} />
              </span>
            </label>
          </li>
        ))}
      </ul>

      <button
        type="button"
        disabled={selectedStoreId === null || isProcessing}
        onClick={() => selectedStoreId !== null && onApprove(selectedStoreId)}
        className="flex h-10 w-full items-center justify-center rounded-lg bg-[#3b82f6] font-sans text-sm font-semibold leading-5 text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6] disabled:opacity-50"
      >
        {/* 가게를 고르지 않으면 승인할 수 없다 — 서버도 storeId 없이는 400 이다. */}
        {selectedStoreId === null ? '가게를 먼저 고르세요' : '이 가게로 승인하기'}
      </button>
    </div>
  );
}

export default StoreCandidatePicker;
