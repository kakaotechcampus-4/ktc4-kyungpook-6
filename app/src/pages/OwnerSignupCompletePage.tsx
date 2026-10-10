import logo from "../assets/good-radar-logo.png";
import PrimaryButton from "../components/ui/PrimaryButton";
import ApprovalProgressTrack from "../components/signup/ApprovalProgressTrack";
import { useSignupComplete } from "../hooks/signupComplete";

/* 로고 뒤의 노란 빛. 피그마 radialGradient 값 그대로. */
const GLOW_BACKGROUND =
  "radial-gradient(circle closest-side, rgb(255 248 225) 0%, rgb(253 239 196) 60%, rgb(253 239 196 / 0) 100%)";

/** 회원가입 완료: 관리자 승인을 기다린다. */
function OwnerSignupCompletePage() {
  const { status, goLogin } = useSignupComplete();
  const approved = status === "APPROVED";

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <section className="flex flex-1 flex-col items-center justify-center">
        <div
          className="mb-6 flex size-[140px] items-center justify-center rounded-full"
          style={{ background: GLOW_BACKGROUND }}
        >
          <img src={logo} alt="" className="size-20" />
        </div>
        <h1 className="pb-2 text-center text-[22px] leading-[normal] font-bold text-[#1f2937]">
          가입 요청이 완료됐어요
        </h1>
        <p className="text-center text-[15px] leading-6 text-[#6b7280]">
          관리자가 확인한 뒤 승인되면
          <br />
          바로 사용할 수 있어요.
        </p>
        <div className="w-full pt-9">
          <ApprovalProgressTrack approved={approved} />
        </div>
      </section>

      <PrimaryButton disabled={!approved} onClick={goLogin}>
        로그인하러 가기
      </PrimaryButton>
    </main>
  );
}

export default OwnerSignupCompletePage;
