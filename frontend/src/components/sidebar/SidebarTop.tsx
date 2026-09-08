import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';

type SidebarTopProps = Omit<ComponentPropsWithoutRef<'header'>, 'children'>;

/** 사이드바 상단 브랜드 헤더. Figma sidebarTop(176:60) */
function SidebarTop({ className, ...props }: SidebarTopProps) {
  return (
    <header
      className={[
        'flex h-14 w-full shrink-0 items-center justify-center overflow-clip border-b border-solid border-[#e2e8f0] py-4 pl-3.5 pr-4',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <div className="flex shrink-0 items-end justify-center gap-1">
        <Icon icon="ri:radar-fill" className="size-8 shrink-0 text-[#eab308]" />
        <div className="flex shrink-0 flex-col items-start justify-center whitespace-nowrap font-sans text-xs leading-4 text-black">
          <p className="mb-[-2px] shrink-0 font-medium">선한레이더</p>
          <p className="shrink-0 font-semibold">Good Radar</p>
        </div>
      </div>
    </header>
  );
}

export default SidebarTop;
