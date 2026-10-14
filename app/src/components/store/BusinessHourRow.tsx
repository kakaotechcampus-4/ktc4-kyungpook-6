import chevronDownIcon from "../../assets/chevron-down-icon.svg";
import closeIcon from "../../assets/close-icon.svg";
import type { BusinessDay, BusinessHour } from "../../services/store";
import { BUSINESS_DAYS, DAY_LABELS } from "../../utils/storeManage";

type BusinessHourRowProps = {
  hour: BusinessHour;
  onChange: (change: Partial<BusinessHour>) => void;
  onRemove: () => void;
};

const BOX_CLASS = "h-11 rounded-xl bg-[#f1f4f8] text-[15px] leading-[normal] text-[#1f2937]";

/** 영업시간 한 줄: 요일 · 여는 시간 – 닫는 시간 · 삭제. */
function BusinessHourRow({ hour, onChange, onRemove }: BusinessHourRowProps) {
  const label = DAY_LABELS[hour.day];

  return (
    <div className="flex items-center gap-3">
      <div className="relative w-24 shrink-0">
        <select
          aria-label="요일"
          value={hour.day}
          onChange={(event) => onChange({ day: event.target.value as BusinessDay })}
          className={`${BOX_CLASS} w-full appearance-none pr-8 pl-3.5 outline-none`}
        >
          {BUSINESS_DAYS.map((day) => (
            <option key={day} value={day}>
              {DAY_LABELS[day]}
            </option>
          ))}
        </select>
        <img
          src={chevronDownIcon}
          alt=""
          width={18}
          height={18}
          className="pointer-events-none absolute top-1/2 right-3.5 -translate-y-1/2"
        />
      </div>
      <input
        aria-label={`${label} 여는 시간`}
        inputMode="numeric"
        placeholder="00:00"
        value={hour.open}
        onChange={(event) => onChange({ open: event.target.value })}
        className={`${BOX_CLASS} w-0 min-w-0 flex-1 px-3.5 text-center outline-none placeholder:text-[#9ca3af]`}
      />
      <span aria-hidden className="text-sm leading-[normal] text-[#9ca3af]">
        –
      </span>
      <input
        aria-label={`${label} 닫는 시간`}
        inputMode="numeric"
        placeholder="00:00"
        value={hour.close}
        onChange={(event) => onChange({ close: event.target.value })}
        className={`${BOX_CLASS} w-0 min-w-0 flex-1 px-3.5 text-center outline-none placeholder:text-[#9ca3af]`}
      />
      <button
        type="button"
        aria-label={`${label} ${hour.open}~${hour.close} 삭제`}
        onClick={onRemove}
        className="flex size-7 shrink-0 items-center justify-center"
      >
        <img src={closeIcon} alt="" width={18} height={18} />
      </button>
    </div>
  );
}

export default BusinessHourRow;
