import type { OpenMode } from "../../hooks/ownerHome";

const MODES: { mode: OpenMode; label: string }[] = [
  { mode: "SCHEDULE", label: "영업시간대로" },
  { mode: "MANUAL", label: "직접 켜고 끄기" },
];

type OpenStatusCardProps = {
  isOpen: boolean;
  mode: OpenMode;
  onModeChange: (mode: OpenMode) => void;
  onToggle: () => void;
};

/**
 * 홈의 "지금 영업 상태" 카드. 영업시간대로면 스위치 없이 상태만, 직접 켜고 끄기면 스위치(영업중 초록·영업종료 빨강)를 보여 준다.
 */
function OpenStatusCard({ isOpen, mode, onModeChange, onToggle }: OpenStatusCardProps) {
  return (
    <section className="flex flex-col gap-1.5 rounded-[20px] border border-[#e5e7eb] bg-white p-5">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1 pb-1">
          <p className="text-sm leading-[normal] text-[#6b7280]">지금 영업 상태</p>
          <p className="text-2xl leading-[normal] font-bold text-[#1f2937]">{isOpen ? "영업중" : "영업종료"}</p>
        </div>
        {mode === "MANUAL" && (
          <button
            type="button"
            role="switch"
            aria-checked={isOpen}
            aria-label="영업 상태"
            onClick={onToggle}
            className={`flex h-8 w-14 shrink-0 rounded-full p-[3px] transition-colors ${
              isOpen ? "justify-end bg-[#1f9d55]" : "justify-start bg-[#e5484d]"
            }`}
          >
            <span className="size-[26px] rounded-full bg-white" />
          </button>
        )}
      </div>

      <div role="radiogroup" aria-label="영업 상태 정하는 방법" className="flex gap-1 rounded-xl bg-[#f1f4f8] p-1">
        {MODES.map(({ mode: value, label }) => {
          const isSelected = mode === value;
          return (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={isSelected}
              onClick={() => onModeChange(value)}
              className={`flex h-10 flex-1 items-center justify-center rounded-[9px] text-sm leading-[normal] font-semibold ${
                isSelected ? "bg-white text-[#1f2937] drop-shadow-[0px_1px_1.5px_rgba(0,0,0,0.08)]" : "text-[#6b7280]"
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>
    </section>
  );
}

export default OpenStatusCard;
