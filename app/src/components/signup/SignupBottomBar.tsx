import type { ReactNode } from "react";

type SignupBottomBarProps = {
  children: ReactNode;
  /** 위쪽 여백 등. 내용과 버튼 사이 간격을 화면마다 맞춘다. */
  className?: string;
};

/**
 * 가입 화면 아래의 진행 막대·"다음" 버튼 영역. 내용이 짧으면 맨 아래에 놓이고(mt-auto),
 * 길어서 스크롤해도 바닥에 붙어 있다(sticky). 위 24px 은 투명에서 흰색으로 이어지고 그 아래는 흰 배경이라,
 * 스크롤한 내용이 버튼 밑으로 들어갈 때 겹쳐 보이지 않고 서서히 사라진다. 끝까지 내리면 내용을 가리지 않는다.
 * 화면 좌우 여백(px-5)까지 덮도록 -mx-5 로 넓히고 안쪽 여백으로 되돌린다.
 */
function SignupBottomBar({ children, className }: SignupBottomBarProps) {
  return (
    <div
      className={[
        // 빈 여백은 뒤 내용을 누를 수 있게 터치를 통과시키고, 버튼·막대만 받는다.
        "pointer-events-none sticky bottom-0 -mx-5 mt-auto bg-[linear-gradient(to_bottom,rgb(255_255_255/0),white_24px)] px-5 pb-[calc(40px+env(safe-area-inset-bottom))] [&>*]:pointer-events-auto",
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
