import type { StoreSummaryRow } from "../../hooks/signupStore";

type StoreSummaryCardProps = {
  rows: StoreSummaryRow[];
  /** "수정". 요약을 접고 입력 칸으로 바꾼다. */
  onEdit: () => void;
};

/** 가게 등록 1 의 "사업자등록증에서 가져왔어요" 요약(상호명·주소). */
function StoreSummaryCard({ rows, onEdit }: StoreSummaryCardProps) {
  return (
    <section className="pb-6">
      <div className="flex items-center justify-between pb-2.5">
        <h2 className="text-sm leading-[normal] font-bold text-[#1f2937]">사업자등록증에서 가져왔어요</h2>
        <button
          type="button"
          onClick={onEdit}
          className="text-xs leading-[normal] font-medium text-[#6b7280] underline"
        >
          수정
        </button>
      </div>
      <dl className="rounded-[14px] border border-[#e6e9ee] bg-[#f8f9fb] px-4 py-1">
        {rows.map(({ label, value }) => (
          <div
            key={label}
            className="flex items-start gap-4 border-b border-[#e6e9ee] py-3 last:border-b-0"
          >
            <dt className="w-[50px] shrink-0 text-[13px] leading-[17px] text-[#6b7280]">{label}</dt>
            <dd className="min-w-0 flex-1 text-sm leading-[17px] font-medium break-words text-[#1f2937]">
              {value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export default StoreSummaryCard;
