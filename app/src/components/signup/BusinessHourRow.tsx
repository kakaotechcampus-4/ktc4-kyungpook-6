import chevronDownIcon from "../../assets/chevron-down-icon.svg";
import removeIcon from "../../assets/remove-icon.svg";
import type { SignupBusinessHour } from "../../hooks/signupDraft";
import { BUSINESS_DAYS, CLOSE_TIME_OPTIONS, OPEN_TIME_OPTIONS } from "../../utils/businessHours";

type BusinessHourRowProps = {
  line: SignupBusinessHour;
  onChange: (field: "day" | "open" | "close", value: string) => void;
  onRemove: () => void;
};

const SELECT_BASE =
  "h-11 appearance-none rounded-xl bg-[#f1f4f8] text-[15px] leading-[normal] outline-none focus-visible:ring-2 focus-visible:ring-[#e3b23c]";

/** 아직 고르지 않은 칸은 연한 글자로 보여 준다. */
const valueColor = (value: string) => (value === "" ? "text-[#a9afbb]" : "text-[#1f2937]");

/** 영업시간 한 줄: 요일 · 여는 시간 – 닫는 시간 · 지우기. */
function BusinessHourRow({ line, onChange, onRemove }: BusinessHourRowProps) {
  return (
    <div className="flex items-center gap-3 pb-1.5">
      <div className="relative w-24 shrink-0">
        <select
          aria-label="요일"
          value={line.day}
          onChange={(event) => onChange("day", event.target.value)}
          className={`${SELECT_BASE} w-full pr-8 pl-3.5 ${valueColor(line.day)}`}
        >
          <option value="" disabled hidden />
          {BUSINESS_DAYS.map(({ value, label }) => (
            <option key={value} value={value}>
              {label}
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

      <select
        aria-label="여는 시간"
        value={line.open}
        onChange={(event) => onChange("open", event.target.value)}
        className={`${SELECT_BASE} min-w-0 flex-1 px-3.5 text-center [text-align-last:center] ${valueColor(line.open)}`}
      >
        <option value="" disabled hidden>
          00:00
        </option>
        {OPEN_TIME_OPTIONS.map((time) => (
          <option key={time} value={time}>
            {time}
          </option>
        ))}
      </select>

      <span aria-hidden="true" className="text-sm leading-[normal] text-[#6b7280]">
        –
      </span>

      <select
        aria-label="닫는 시간"
        value={line.close}
        onChange={(event) => onChange("close", event.target.value)}
        className={`${SELECT_BASE} min-w-0 flex-1 px-3.5 text-center [text-align-last:center] ${valueColor(line.close)}`}
      >
        <option value="" disabled hidden>
          00:00
        </option>
        {CLOSE_TIME_OPTIONS.map((time) => (
          <option key={time} value={time}>
            {time}
          </option>
        ))}
      </select>

      <button
        type="button"
        aria-label="이 영업시간 지우기"
        onClick={onRemove}
        className="flex size-7 shrink-0 items-center justify-center"
      >
        <img src={removeIcon} alt="" width={18} height={18} />
      </button>
    </div>
  );
}

export default BusinessHourRow;
