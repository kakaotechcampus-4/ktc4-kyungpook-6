import { Navigate } from "react-router-dom";
import PrimaryButton from "../components/ui/PrimaryButton";
import AutofillChecklist from "../components/signup/AutofillChecklist";
import BizCertDocCard from "../components/signup/BizCertDocCard";
import GlowHeader from "../components/signup/GlowHeader";
import { useBizCertReader, useBizCertResult } from "../hooks/signupBizCert";
import { useSignupDraft } from "../hooks/signupDraft";

/** 사업자등록증을 읽은 결과. 맞는지는 다음 화면(기본 정보)에서 확인한다. */
function OwnerSignupBizCertResultPage() {
  const { account } = useSignupDraft();
  const { file, items, readCount, goNext } = useBizCertResult();
  const { read, isReading, errorMessage } = useBizCertReader();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !file) return <Navigate to="/owner/signup" replace />;

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <div className="pt-18">
        {readCount > 0 ? (
          <GlowHeader
            title="정보를 가져왔어요"
            description={
              <>
                다음 화면에서 맞는지
                <br />
                한 번만 확인해 주세요.
              </>
            }
          />
        ) : (
          <GlowHeader
            title="정보를 읽지 못했어요"
            description={
              <>
                다음 화면에서 직접 입력하거나
                <br />
                다시 올려 주세요.
              </>
            }
          />
        )}
      </div>

      <div className="pt-7">
        <BizCertDocCard
          id="signup-biz-cert-reupload"
          file={file}
          readCount={readCount}
          onReupload={read}
          disabled={isReading}
        />
      </div>

      <div className="pt-5">
        <AutofillChecklist items={items} />
      </div>

      <div className="flex-1" />

      {/* 올리기 실패 문구 자리. 높이를 고정해 두어 문구가 떠도 버튼이 움직이지 않는다. */}
      <div className="flex min-h-12 items-center justify-center">
        {errorMessage && (
          <p role="alert" className="text-center text-[13px] leading-[1.4] text-[#e5484d]">
            {errorMessage}
          </p>
        )}
      </div>

      <PrimaryButton disabled={isReading} onClick={goNext}>
        다음
      </PrimaryButton>
    </main>
  );
}

export default OwnerSignupBizCertResultPage;
