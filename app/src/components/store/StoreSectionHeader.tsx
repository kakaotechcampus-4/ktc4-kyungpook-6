import type { ReactNode } from "react";

type StoreSectionHeaderProps = {
  title: string;
  /** 제목 오른쪽에 붙는 버튼("시간 추가" 등). */
  action?: ReactNode;
  className?: string;
};

/** 가게 관리 화면의 구역 제목(기본 정보·영업시간·메뉴). */
function StoreSectionHeader({ title, action, className }: StoreSectionHeaderProps) {
  return (
    <div className={["flex items-center justify-between pb-3", className].filter(Boolean).join(" ")}>
      <h2 className="text-[17px] leading-[normal] font-bold text-[#1f2937]">{title}</h2>
      {action}
    </div>
  );
}

export default StoreSectionHeader;
