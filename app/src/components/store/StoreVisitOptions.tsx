import type { ReactNode } from "react";
import type { CompanionPolicy } from "../../services/store";

const COMPANIONS: { value: CompanionPolicy; label: string }[] = [
  { value: "NONE", label: "아이 혼자만" },
  { value: "ONE", label: "1명까지 함께" },
  { value: "TWO", label: "2명 까지 함께" },
];

const CHIP_CLASS =
  "flex h-[46px] flex-1 items-center justify-center rounded-xl text-sm leading-[17px] tracking-[-0.16px]";
const SELECTED_CLASS =
  "border-2 border-[rgba(227,178,60,0.3)] bg-[rgba(227,178,60,0.3)] font-semibold text-[#b8891a]";
const UNSELECTED_CLASS =
  "border border-[#e5e7eb] bg-[#fafbfc] font-medium text-[#a9afbb]";

type ChipProps = {
  label: string;
  selected: boolean;
  onClick: () => void;
  role: "radio" | "checkbox";
};

function Chip({ label, selected, onClick, role }: ChipProps) {
  return (
    <button
      type="button"
      role={role}
      aria-checked={selected}
      onClick={onClick}
      className={`${CHIP_CLASS} ${selected ? SELECTED_CLASS : UNSELECTED_CLASS}`}
    >
      {label}
    </button>
  );
}

function Group({
  label,
  role,
  children,
}: {
  label: string;
  role: "radiogroup" | "group";
  children: ReactNode;
}) {
  return (
    <div>
      <p className="pb-2.5 text-sm leading-[17px] font-semibold tracking-[-0.16px] text-[#6b7280]">
        {label}
      </p>
      <div role={role} aria-label={label} className="flex gap-2">
        {children}
      </div>
    </div>
  );
}

type StoreVisitOptionsProps = {
  companion: CompanionPolicy;
  onCompanionChange: (value: CompanionPolicy) => void;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  onToggleTakeout: () => void;
  onToggleDineIn: () => void;
};

/**
 * 가게 관리 기본 정보 아래의 방문 조건(피그마 759:1744). 동반 인원은 하나만, 이용 방법은 둘 다 고를 수 있다.
 * 이용 방법은 하나는 켜져 있어야 해서(백엔드 OperatingInfoRequest) 마지막 하나는 끌 수 없다 — 훅이 막는다.
 */
function StoreVisitOptions({
  companion,
  onCompanionChange,
  takeoutAvailable,
  dineInAvailable,
  onToggleTakeout,
  onToggleDineIn,
}: StoreVisitOptionsProps) {
  return (
    <div className="flex flex-col gap-4">
      <Group label="아이와 함께 올 수 있는 인원" role="radiogroup">
        {COMPANIONS.map(({ value, label }) => (
          <Chip
            key={value}
            role="radio"
            label={label}
            selected={companion === value}
            onClick={() => onCompanionChange(value)}
          />
        ))}
      </Group>
      <Group label="아이가 이용할 수 있는 방법" role="group">
        <Chip
          role="checkbox"
          label="포장해 가기"
          selected={takeoutAvailable}
          onClick={onToggleTakeout}
        />
        <Chip
          role="checkbox"
          label="매장에서 이용하기"
          selected={dineInAvailable}
          onClick={onToggleDineIn}
        />
      </Group>
    </div>
  );
}

export default StoreVisitOptions;
