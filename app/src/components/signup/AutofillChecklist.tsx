import checkOnIcon from "../../assets/autofill-check-on-icon.svg";
import checkOffIcon from "../../assets/autofill-check-off-icon.svg";
import type { AutofillItem } from "../../utils/bizCertReading";

type AutofillChecklistProps = {
  items: AutofillItem[];
  /** 목록 위 작은 제목. 없으면 목록만. */
  title?: string;
};

/** 사업자등록증에서 채우는 항목 목록. 읽은 항목은 노란 원, 못 읽은 항목은 회색 테두리 원. */
function AutofillChecklist({ items, title }: AutofillChecklistProps) {
  return (
    <section className="rounded-2xl bg-[#f8f9fb] p-[18px]">
      {title && <h2 className="pb-3 text-xs leading-[normal] font-semibold text-[#6b7280]">{title}</h2>}
      <ul className="flex flex-col gap-[11px]">
        {items.map(({ label, read }) => (
          <li key={label} className="flex items-center gap-3">
            <span
              className={[
                "flex size-[22px] shrink-0 items-center justify-center rounded-full",
                read ? "bg-[#e3b23c]" : "border-[1.5px] border-[#a9afbb]",
              ].join(" ")}
            >
              <img src={read ? checkOnIcon : checkOffIcon} alt="" width={12} height={12} />
            </span>
            <span className="text-sm leading-[normal] text-[#1f2937]">
              {label}
              <span className="sr-only">{read ? " 채움" : " 직접 입력"}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default AutofillChecklist;
