import type { ComponentPropsWithoutRef, ReactNode } from "react";

type TextFieldProps = ComponentPropsWithoutRef<"input"> & {
  id: string;
  label: string;
  /** 입력 칸 오른쪽에 붙는 것(비밀번호 보기 버튼 등). */
  trailing?: ReactNode;
};

/** 라벨이 위에 붙은 회색 입력 칸. */
function TextField({ id, label, trailing, className, ...props }: TextFieldProps) {
  return (
    <div className={className}>
      <label htmlFor={id} className="block pb-2 text-sm leading-[normal] text-[#6b7280]">
        {label}
      </label>
      <div className="flex h-13 items-center gap-2 rounded-xl bg-[#f1f4f8] px-4">
        <input
          id={id}
          className="min-w-0 flex-1 bg-transparent text-base leading-[normal] text-[#1f2937] outline-none"
          {...props}
        />
        {trailing}
      </div>
    </div>
  );
}

export default TextField;
