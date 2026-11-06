import { Navigate } from "react-router-dom";
import PrimaryButton from "../components/ui/PrimaryButton";
import AutofillChecklist from "../components/signup/AutofillChecklist";
import BizCertDocCard from "../components/signup/BizCertDocCard";
import BizCertScreen from "../components/signup/BizCertScreen";
import UploadErrorSlot from "../components/signup/UploadErrorSlot";
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
    <BizCertScreen
      title={readCount > 0 ? "정보를 가져왔어요" : "정보를 읽지 못했어요"}
      description={
        readCount > 0 ? (
          <>
            다음 화면에서 맞는지
            <br />
            한 번만 확인해 주세요.
          </>
        ) : (
          <>
            다음 화면에서 직접 입력하거나
            <br />
            다시 올려 주세요.
          </>
        )
      }
      bottom={
        <>
          <UploadErrorSlot message={errorMessage} />
          <PrimaryButton disabled={isReading} onClick={goNext}>
            다음
          </PrimaryButton>
        </>
      }
    >
      <BizCertDocCard
        id="signup-biz-cert-reupload"
        file={file}
        readCount={readCount}
        onReupload={read}
        disabled={isReading}
      />
      <AutofillChecklist items={items} />
    </BizCertScreen>
  );
}

export default OwnerSignupBizCertResultPage;
