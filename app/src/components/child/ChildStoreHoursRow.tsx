import chevronDownIcon from "../../assets/child-chevron-down-icon.svg";
import clockIcon from "../../assets/child-clock-icon.svg";
import type { BusinessDay, BusinessHour } from "../../services/store";
import { WEEK_DAYS, formatDayHours } from "../../utils/childStoreStatus";

type ChildStoreHoursRowProps = {
  businessHours: BusinessHour[];
  today: BusinessDay;
  expanded: boolean;
  onToggle: () => void;
};

/** 가게 정보의 영업시간 줄. 오늘 영업시간을 보여 주고, 누르면 요일별 목록을 펼친다. */
function ChildStoreHoursRow({ businessHours, today, expanded, onToggle }: ChildStoreHoursRowProps) {
  const todayShort = WEEK_DAYS.find(({ day }) => day === today)?.short;

  return (
    <div className="flex items-start gap-3 border-b border-[#e6e9ee] px-4 py-3.5">
      <img src={clockIcon} alt="" width={20} height={20} />
      <div className="min-w-0 flex-1">
        <button
          type="button"
          aria-expanded={expanded}
          onClick={onToggle}
          className="flex w-full items-center gap-1.5 text-left text-sm leading-5"
        >
          <span className="text-[#6b7280]">오늘({todayShort})</span>
          <b className="font-bold text-[#1f2937]">{formatDayHours(businessHours, today)}</b>
          <span className="ml-auto flex size-[18px] shrink-0 items-center justify-center">
            <img
              src={chevronDownIcon}
              alt=""
              width={10}
              height={6}
              className={expanded ? "rotate-180" : undefined}
            />
          </span>
        </button>

        {expanded && (
          <ul className="mt-3 flex flex-col gap-2 border-t border-dashed border-[#e6e9ee] pt-3">
            {WEEK_DAYS.map(({ day, label }) => {
              const isToday = day === today;
              return (
                <li
                  key={day}
                  className={`flex justify-between text-[13px] leading-5 ${isToday ? "font-bold text-[#1f2937]" : "text-[#6b7280]"}`}
                >
                  <span>{label}</span>
                  <span>{formatDayHours(businessHours, day)}</span>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

export default ChildStoreHoursRow;
