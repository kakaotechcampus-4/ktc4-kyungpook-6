import type { OpenMode } from "../../services/store";

const MODES: { mode: OpenMode; label: string }[] = [
  { mode: "SCHEDULE", label: "영업시간대로" },
  { mode: "MANUAL", label: "직접 켜고 끄기" },
];

type OpenStatusCardProps = {
  /** 큰 글자. 영업시간대로면 영업전·영업중·영업종료·휴무중, 직접 켜고 끄기면 주문 ON·주문 OFF. */
  statusLabel: string;
  mode: OpenMode;
  /** 직접 켜고 끄기의 스위치가 켜져 있는가. */
  manualOpen: boolean;
  /** 저장 중이면 true. 스위치·방식 버튼을 잠근다. */
  disabled: boolean;
  onModeChange: (mode: OpenMode) => void;
  onToggle: () => void;
};

/**
 * 홈의 "지금 영업 상태" 카드(피그마 "div.status-card"). 영업시간대로면 스위치 없이 상태만,
 * 직접 켜고 끄기면 스위치(주문 ON 초록·주문 OFF 빨강)를 보여 준다.
 */
function OpenStatusCard({ statusLabel, mode, manualOpen, disabled, onModeChange, onToggle }: OpenStatusCardProps) {
  return (
    <section className="flex flex-col gap-1.5 rounded-[20px] border border-[#e5e7eb] bg-white p-5">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1 pb-1">
          <p className="text-sm leading-[normal] text-[#6b7280]">지금 영업 상태</p>
          <p className="text-2xl leading-[normal] font-bold text-[#1f2937]">{statusLabel}</p>
        </div>
        {mode === "MANUAL" && (
          <button
            type="button"
            role="switch"
            aria-checked={manualOpen}
            aria-label="주문 받기"
            onClick={onToggle}
            disabled={disabled}
            className={`flex h-8 w-14 shrink-0 rounded-full p-[3px] transition-colors ${
              manualOpen ? "justify-end bg-[#1f9d55]" : "justify-start bg-[#e5484d]"
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
              disabled={disabled}
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
