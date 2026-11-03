import type { ReactNode } from "react";
import logo from "../../assets/good-radar-logo.png";

/* 로고 뒤의 노란 빛. 피그마 radialGradient 값 그대로. */
const GLOW_BACKGROUND =
  "radial-gradient(circle closest-side, rgb(255 248 225) 0%, rgb(253 239 196) 60%, rgb(253 239 196 / 0) 100%)";

type GlowHeaderProps = {
  title: string;
  /** 줄을 나누려면 <br /> 을 넣는다. */
  description: ReactNode;
};

/** 노란 빛 위의 큰 로고와 가운데 정렬 제목·설명. 사업자등록증 올리기·결과, 가입 완료 화면이 같이 쓴다. */
function GlowHeader({ title, description }: GlowHeaderProps) {
  return (
    <header className="flex flex-col items-center">
      <div
        className="mb-6 flex size-[140px] items-center justify-center rounded-full"
        style={{ background: GLOW_BACKGROUND }}
      >
        <img src={logo} alt="" className="size-20" />
      </div>
      <h1 className="pb-2 text-center text-[22px] leading-[normal] font-bold text-[#1f2937]">{title}</h1>
      <p className="text-center text-[15px] leading-6 text-[#6b7280]">{description}</p>
    </header>
  );
}

export default GlowHeader;
