import type { PendingStoreResponse } from '../../services/analysis';
import { toStorePhoneLabel } from '../../hooks/analysis';

/** 조사 결과가 아직 없는 대상 가게. 결과 카드와 같은 크기·테두리로 아래쪽 목록에 둔다. */
function PendingSurveyStoreCard({ storeName, storeAddress, storePhone }: PendingStoreResponse) {
  const phone = toStorePhoneLabel(storePhone);

  return (
    <article className="flex w-full flex-col gap-3 rounded-lg border border-solid border-[#e2e8f0] bg-white p-3">
      <div className="flex w-full items-start justify-between gap-2">
        <h3 className="min-w-0 flex-1 truncate font-sans text-base font-semibold leading-6 text-black">
          {storeName}
        </h3>
        <div className="flex max-w-[60%] shrink-0 flex-col items-end gap-0.5">
          <p className="max-w-full truncate font-sans text-sm leading-5 text-[#1e293b]">
            {storeAddress}
          </p>
          <p className={`max-w-full truncate font-sans text-sm leading-5 ${phone.issue === 'missing' ? 'text-[#94a3b8]' : 'text-[#1e293b]'}`}>
            {phone.value}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t border-solid border-[#f1f5f9] pt-3 font-sans text-sm leading-5">
        <span className="rounded-md bg-[#f1f5f9] px-2 py-0.5 font-medium text-[#475569]">조사 전</span>
        <span className="text-[#64748b]">조사 차례를 기다리고 있습니다.</span>
      </div>
    </article>
  );
}

export default PendingSurveyStoreCard;
