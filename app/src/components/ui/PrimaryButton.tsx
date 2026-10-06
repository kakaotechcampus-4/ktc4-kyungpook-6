import type { ComponentPropsWithoutRef } from "react";

type PrimaryButtonProps = ComponentPropsWithoutRef<"button">;

/** 화면 아래의 노란 큰 버튼("다음" 등). 누를 수 없을 때는 배경만 연해진다. */
function PrimaryButton({ className, type = "button", ...props }: PrimaryButtonProps) {
  return (
    <button
      type={type}
      className={[
        "flex h-13 w-full items-center justify-center rounded-xl bg-[#e3b23c] text-base leading-[normal] font-semibold text-white disabled:bg-[#e3b23c]/30",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      {...props}
    />
  );
}

export default PrimaryButton;
