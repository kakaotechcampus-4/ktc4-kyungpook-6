import type { ReactNode } from "react";
import GlowHeader from "./GlowHeader";
import SignupBottomBar from "./SignupBottomBar";

type BizCertScreenProps = {
  title: string;
  description: ReactNode;
  /** 헤더 아래 내용(등록증 카드·자동 채우기 목록). */
  children: ReactNode;
  /** 바닥의 실패 문구·버튼. */
  bottom: ReactNode;
};

/*
  키 작은 화면(높이 760px 이하, 예: iPhone SE 667)에서는 간격을 줄인다. 로고도 GlowHeader 가 같은 기준으로 줄인다.
  이렇게 해야 결과 화면(헤더·등록증 카드·목록 5줄·버튼)이 667 에서도 잘리지 않는다.
*/
const CONTENT_CLASS_NAME =
  "flex min-h-0 flex-1 flex-col justify-center gap-6 py-4 [@media(max-height:760px)]:gap-3 [@media(max-height:760px)]:py-2";

/**
 * 사업자등록증 올리기·결과 화면의 공통 틀. 입력 칸이 없는 화면이라 스크롤하지 않고 한 화면에 맞춘다.
 * 내용은 헤더와 버튼 사이 가운데에 놓는다.
 */
function BizCertScreen({ title, description, children, bottom }: BizCertScreenProps) {
  return (
    <main className="flex h-dvh flex-col overflow-hidden bg-white px-5 pt-[env(safe-area-inset-top)]">
      <div className={CONTENT_CLASS_NAME}>
        <GlowHeader title={title} description={description} />
        {children}
      </div>
      <SignupBottomBar>{bottom}</SignupBottomBar>
    </main>
  );
}

export default BizCertScreen;
