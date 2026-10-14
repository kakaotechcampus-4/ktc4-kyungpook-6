type ToggleSwitchProps = {
  checked: boolean;
  onChange: () => void;
  /** 화면 읽기 프로그램이 읽는 이름. */
  label: string;
};

/** 켜면 초록, 끄면 회색인 스위치. */
function ToggleSwitch({ checked, onChange, label }: ToggleSwitchProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onChange}
      className={`flex h-[26px] w-11 shrink-0 rounded-full p-[3px] transition-colors ${
        checked ? "justify-end bg-[#1f9d55]" : "justify-start bg-[#d1d5db]"
      }`}
    >
      <span className="size-5 rounded-full bg-white" />
    </button>
  );
}

export default ToggleSwitch;
