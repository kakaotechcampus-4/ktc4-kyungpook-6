import type { ReactNode } from "react";

export type CheckInInfoRow = {
  label: string;
  value: ReactNode;
};

type CheckInInfoCardProps = {
  rows: CheckInInfoRow[];
};

/** 체크인 결과의 "라벨 — 값" 목록 카드. */
function CheckInInfoCard({ rows }: CheckInInfoCardProps) {
  return (
    <dl className="flex w-full flex-col rounded-2xl border border-[#e5e7eb] bg-white p-4">
      {rows.map(({ label, value }) => (
        <div key={label} className="flex items-start justify-between py-2.5">
          <dt className="text-[15px] leading-[normal] text-[#6b7280]">{label}</dt>
          <dd className="text-[15px] leading-[normal] text-[#1f2937]">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

export default CheckInInfoCard;
