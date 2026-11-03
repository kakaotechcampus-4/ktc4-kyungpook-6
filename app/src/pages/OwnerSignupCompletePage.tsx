import PrimaryButton from "../components/ui/PrimaryButton";
import ApprovalProgressTrack from "../components/signup/ApprovalProgressTrack";
import GlowHeader from "../components/signup/GlowHeader";
import { useSignupComplete } from "../hooks/signupComplete";

/** 회원가입 완료: 관리자 승인을 기다린다. */
function OwnerSignupCompletePage() {
  const { status, goLogin } = useSignupComplete();
  const approved = status === "APPROVED";

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <section className="flex flex-1 flex-col items-center justify-center">
        <GlowHeader
          title="가입 요청이 완료됐어요"
          description={
            <>
              관리자가 확인한 뒤 승인되면
              <br />
              바로 사용할 수 있어요.
            </>
          }
        />
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
