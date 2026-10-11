import { useId } from 'react';
import type { ComponentPropsWithoutRef } from 'react';

type InputFieldProps = Omit<ComponentPropsWithoutRef<'input'>, 'id'> & {
  label: string;
};

/** 라벨 + 입력 칸. Figma Input Field(515:20) */
function InputField({ label, className, ...props }: InputFieldProps) {
  const id = useId();

  return (
    <div
      className={['flex w-80 flex-col items-start gap-1', className]
        .filter(Boolean)
        .join(' ')}
    >
      <label
        htmlFor={id}
        className="whitespace-nowrap font-sans text-sm leading-5 font-normal text-[#1e293b]"
      >
        {label}
      </label>
      <input
        id={id}
        className="w-full rounded bg-[#f1f5f9] p-2.5 font-sans text-sm leading-5 font-medium text-[#1e293b] outline-none placeholder:text-[#64748b] focus-visible:ring-2 focus-visible:ring-[#3b82f6]"
        {...props}
      />
    </div>
  );
}

export default InputField;
