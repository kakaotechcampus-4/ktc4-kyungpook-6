type RoleCardProps = {
  icon: string;
  title: string;
  description: string;
  selected: boolean;
  onSelect: () => void;
};

/** 역할 선택 화면의 카드 한 장. 고르면 노란 테두리와 배경이 들어간다. */
function RoleCard({ icon, title, description, selected, onSelect }: RoleCardProps) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={[
        "flex w-full items-center gap-4 rounded-[20px] border px-5 py-6 text-left",
        selected ? "border-[#e3b23c] bg-[#fbf3dc]" : "border-[#e5e7eb] bg-white",
      ].join(" ")}
    >
      <span className="flex size-14 shrink-0 items-center justify-center rounded-2xl border border-[#e5e7eb] bg-white">
        <img src={icon} alt="" width={28} height={28} />
      </span>
      <span className="flex flex-col gap-1">
        <span className="text-lg leading-[normal] font-bold text-[#1f2937]">{title}</span>
        <span className="text-sm leading-[normal] text-[#6b7280]">{description}</span>
      </span>
    </button>
  );
}

export default RoleCard;
