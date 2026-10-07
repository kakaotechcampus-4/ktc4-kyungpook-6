import type { ComponentPropsWithoutRef, ReactNode } from "react";

type TextFieldProps = ComponentPropsWithoutRef<"input"> & {
  id: string;
  label: string;
  /** 입력 칸 오른쪽에 붙는 것(비밀번호 보기 버튼 등). */
  trailing?: ReactNode;
  /** filled: 회색 배경(로그인·기본 정보), outlined: 흰 배경에 테두리(회원가입 계정). */
  variant?: "filled" | "outlined";
  /** 값이 틀렸을 때 빨간 테두리를 그린다. */
  invalid?: boolean;
  labelClassName?: string;
};

const BOX_BASE = "flex h-13 items-center gap-2 rounded-xl border px-4";

const boxClassName = (variant: "filled" | "outlined", invalid: boolean) => {
  if (invalid) return `${BOX_BASE} border-[#e5484d] bg-white`;
  if (variant === "outlined") {
    // 입력 중인 칸은 노란 테두리와 그림자.
    return `${BOX_BASE} border-[#a9afbb] bg-white focus-within:border-[#e3b23c] focus-within:shadow-[0px_2px_4px_0px_rgba(0,0,0,0.25)]`;
  }
  return `${BOX_BASE} border-transparent bg-[#f1f4f8]`;
};

/** 라벨이 위에 붙은 입력 칸. */
function TextField({
  id,
  label,
  trailing,
  variant = "filled",
  invalid = false,
  labelClassName,
  className,
  ...props
}: TextFieldProps) {
  return (
    <div className={className}>
      <label
        htmlFor={id}
        className={["block pb-2 text-sm leading-[normal] text-[#6b7280]", labelClassName]
          .filter(Boolean)
          .join(" ")}
      >
        {label}
      </label>
      <div className={boxClassName(variant, invalid)}>
        <input
          id={id}
          aria-invalid={invalid || undefined}
          className="min-w-0 flex-1 bg-transparent text-base leading-[normal] text-[#1f2937] outline-none placeholder:text-sm placeholder:text-[#a9afbb]"
          {...props}
        />
        {trailing}
      </div>
    </div>
  );
}

export default TextField;
