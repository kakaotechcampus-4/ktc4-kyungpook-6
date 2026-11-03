import { useRef } from "react";
import type { ChangeEvent } from "react";
import { Navigate } from "react-router-dom";
import cameraIcon from "../assets/camera-icon.svg";
import PrimaryButton from "../components/ui/PrimaryButton";
import AutofillChecklist from "../components/signup/AutofillChecklist";
import BizCertScreen from "../components/signup/BizCertScreen";
import UploadErrorSlot from "../components/signup/UploadErrorSlot";
import { BIZ_CERT_ACCEPT } from "../components/signup/bizCertAccept";
import { useBizCertReader } from "../hooks/signupBizCert";
import { useSignupDraft } from "../hooks/signupDraft";
import { toAutofillItems } from "../utils/bizCertReading";

/** 올리기 전에는 모든 항목이 회색이다. */
const PENDING_ITEMS = toAutofillItems(null);

/** 회원가입 2단계: 사업자등록증 올리기. 읽은 값으로 다음 화면들의 칸을 채운다. */
function OwnerSignupBizCertPage() {
  const { account } = useSignupDraft();
  const { read, isReading, errorMessage } = useBizCertReader();
  const inputRef = useRef<HTMLInputElement>(null);

  // 1단계를 거치지 않고 들어오면(새로고침 등) 아이디·비밀번호가 없으니 1단계로 돌려보낸다.
  if (!account) return <Navigate to="/owner/signup" replace />;

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // 뒤로 왔다가 같은 파일을 다시 골라도 change 가 오도록 비운다.
    event.target.value = "";
    if (file) read(file);
  };

  return (
    <BizCertScreen
      title="사업자등록증을 올려주세요"
      description={
        <>
          사진 한 장이면 가게 정보를
          <br />
          자동으로 채워드려요.
        </>
      }
      bottom={
        <>
          <UploadErrorSlot message={errorMessage} />
          <PrimaryButton className="gap-2" disabled={isReading} onClick={() => inputRef.current?.click()}>
            <img src={cameraIcon} alt="" width={20} height={20} />
            {isReading ? "읽는 중이에요" : "사업자등록증 올리기"}
          </PrimaryButton>
          <input
            ref={inputRef}
            type="file"
            accept={BIZ_CERT_ACCEPT}
            className="sr-only"
            tabIndex={-1}
            aria-hidden="true"
            onChange={handleChange}
          />
        </>
      }
    >
      <AutofillChecklist title="자동으로 채워지는 정보" items={PENDING_ITEMS} />
    </BizCertScreen>
  );
}

export default OwnerSignupBizCertPage;
