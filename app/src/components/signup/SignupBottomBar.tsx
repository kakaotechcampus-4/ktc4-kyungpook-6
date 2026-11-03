import type { ReactNode } from "react";

type SignupBottomBarProps = {
  children: ReactNode;
  /** 위쪽 여백 등. 내용과 버튼 사이 간격을 화면마다 맞춘다. */
  className?: string;
};

/**
 * 가입 화면 아래의 진행 막대·"다음" 버튼 영역. 내용이 짧으면 맨 아래에 놓이고(mt-auto),
 * 길어서 스크롤해도 바닥에 붙어 있다(sticky). 배경을 깔지 않아 버튼·막대 사이로 뒤 내용이 보인다.
 */
function SignupBottomBar({ children, className }: SignupBottomBarProps) {
  return (
    <div
      className={[
        // 빈 여백은 뒤 내용을 누를 수 있게 터치를 통과시키고, 버튼·막대만 받는다.
        "pointer-events-none sticky bottom-0 mt-auto pb-[calc(40px+env(safe-area-inset-bottom))] [&>*]:pointer-events-auto",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      {children}
    </div>
  );
}

export default SignupBottomBar;
