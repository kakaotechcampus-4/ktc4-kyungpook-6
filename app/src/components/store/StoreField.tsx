import type { ComponentPropsWithoutRef } from "react";

const LABEL_CLASS = "block pb-2 text-sm leading-[normal] font-medium text-[#4b5563]";
const BOX_CLASS = "flex h-[50px] items-center rounded-xl bg-[#f1f4f8] px-3.5 text-[15px] leading-[normal] text-[#1f2937]";

type StoreReadOnlyFieldProps = {
  label: string;
  value: string;
};

/**
 * 점주가 바꿀 수 없는 값(상호명·주소). 관리자가 사업자등록증을 보고 승인한 값이라
 * 입력 칸이 아니라 글자로만 보여 준다(백엔드 StoreDetailResponse 설명).
 */
export function StoreReadOnlyField({ label, value }: StoreReadOnlyFieldProps) {
  return (
    <div>
      <span className={LABEL_CLASS}>{label}</span>
      <p className={`${BOX_CLASS} truncate`}>{value}</p>
    </div>
  );
}

type StoreInputFieldProps = ComponentPropsWithoutRef<"input"> & {
  id: string;
  label: string;
};

/** 점주가 고치는 값(가게 전화번호). 바꿀 수 없는 칸과 생김새가 같다. */
export function StoreInputField({ id, label, ...props }: StoreInputFieldProps) {
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS}>
        {label}
      </label>
      <input
        id={id}
        className={`${BOX_CLASS} w-full outline-none placeholder:text-[#9ca3af] focus:ring-2 focus:ring-[#e3b23c]`}
        {...props}
      />
    </div>
  );
}
