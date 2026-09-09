import { Icon } from "@iconify/react";
import type { ComponentPropsWithoutRef } from "react";

type NavItemProps = {
  /** Iconify 아이콘 이름. Figma 아이콘 노드명을 그대로 사용한다. (예: `mingcute:list-check-fill`) */
  icon: string;
  label: string;
  /** 선택 상태. Figma navItem1(176:68) = true, navItem2(176:72) = false */
  active?: boolean;
} & Omit<ComponentPropsWithoutRef<"button">, "children">;

function NavItem({
  icon,
  label,
  active = false,
  className,
  ...props
}: NavItemProps) {
  return (
    <button
      type="button"
      aria-current={active ? "page" : undefined}
      className={[
        "flex w-full flex-col items-center justify-center gap-1 overflow-clip p-3 rounded-lg",
        active ? "bg-[#fefce8] text-[#eab308]" : "text-[#475569]",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      {...props}
    >
      <Icon icon={icon} className="size-6 shrink-0" />
      <span
        className={[
          "shrink-0 whitespace-nowrap font-sans text-sm leading-5",
          active ? "font-bold" : "font-semibold",
        ].join(" ")}
      >
        {label}
      </span>
    </button>
  );
}

export default NavItem;
