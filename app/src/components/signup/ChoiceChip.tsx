type ChoiceChipProps = {
  label: string;
  selected: boolean;
  onClick: () => void;
};

/** 가게 등록 2 의 고르기 칩(함께 올 수 있는 인원·식사 방법). 고르면 노랗게 채운다. */
function ChoiceChip({ label, selected, onClick }: ChoiceChipProps) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={[
        "flex h-11.5 min-w-0 flex-1 items-center justify-center rounded-xl text-sm leading-[normal]",
        selected
          ? "border-2 border-[#e3b23c]/30 bg-[#e3b23c]/30 font-semibold text-[#b8891a]"
          : "border border-[#e5e7eb] bg-[#fafbfc] font-medium text-[#a9afbb]",
      ].join(" ")}
    >
      {label}
    </button>
  );
}

export default ChoiceChip;
