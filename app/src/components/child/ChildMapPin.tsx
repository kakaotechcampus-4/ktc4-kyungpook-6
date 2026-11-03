import type { ChildStoreOpenStatus } from "../../utils/childStoreStatus";

type ChildMapPinProps = {
  name: string;
  status: ChildStoreOpenStatus;
  selected: boolean;
  onClick: () => void;
};

/**
 * 지도 위 가게 핀(말풍선 + 꼬리). 꼬리 끝이 가게 위치다.
 * - 영업중: 노란 테두리, 초록 점
 * - 그 밖(쉬는중·휴무 등): 회색 테두리·글자, 빨간 점(목록 배지와 같은 색)
 * - 고른 가게: 노랗게 채운다
 */
function ChildMapPin({ name, status, selected, onClick }: ChildMapPinProps) {
  const isOpen = status === "OPEN";
  const lineColor = selected || isOpen ? "border-[#e2b33c]" : "border-[#e6e9ee]";
  const tailColor = selected || isOpen ? "bg-[#e2b33c]" : "bg-[#e6e9ee]";
  const bubbleColor = selected
    ? "bg-[#e2b33c] text-[#3a2c08]"
    : isOpen
      ? "bg-white text-[#1f2937]"
      : "bg-white text-[#a0a7b2]";

  return (
    <button
      type="button"
      aria-label={`${name} 고르기`}
      aria-pressed={selected}
      onClick={onClick}
      className="flex flex-col items-center"
    >
      <span
        className={`flex items-center gap-[5px] rounded-[20px] border-2 px-2.5 py-[5px] text-xs leading-[normal] font-bold whitespace-nowrap drop-shadow-[0px_3px_5px_rgba(30,38,51,0.15)] ${lineColor} ${bubbleColor}`}
      >
        <span className={`size-[7px] rounded-full ${isOpen ? "bg-[#22a06b]" : "bg-[#f93446]"}`} />
        {name}
      </span>
      <span className={`h-2 w-0.5 ${tailColor}`} />
    </button>
  );
}

export default ChildMapPin;
