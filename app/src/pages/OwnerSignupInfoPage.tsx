import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import BizRegistrationUploadField from "../components/signup/BizRegistrationUploadField";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupInfo } from "../hooks/signupInfo";

const LABEL_CLASS_NAME = "font-semibold";

/** 회원가입 2단계: 대표자·가게 기본 정보. */
function OwnerSignupInfoPage() {
  const { account } = useSignupDraft();
  const {
    representativeName,
    setRepresentativeName,
    phone,
    setPhone,
    storeName,
    setStoreName,
    bizNo,
    setBizNo,
    bizRegistrationFile,
    setBizRegistrationFile,
    canProceed,
    goNext,
    errorMessage,
  } = useSignupInfo();

  // 1단계를 거치지 않고 들어오면(새로고침 등) 아이디·비밀번호가 없으니 1단계로 돌려보낸다.
  if (!account) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="기본 정보" description="기본 정보를 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <TextField
          id="signup-representative-name"
          label="대표자명"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          value={representativeName}
          onChange={(event) => setRepresentativeName(event.target.value)}
          maxLength={50}
          autoComplete="name"
        />
        <TextField
          id="signup-phone"
          label="대표자 전화번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
          type="tel"
          inputMode="numeric"
          autoComplete="tel"
        />
        <TextField
          id="signup-store-name"
          label="상호명"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          value={storeName}
          onChange={(event) => setStoreName(event.target.value)}
          maxLength={200}
        />
        <TextField
          id="signup-biz-no"
          label="사업자등록번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-4"
          value={bizNo}
          onChange={(event) => setBizNo(event.target.value)}
          inputMode="numeric"
        />
        <BizRegistrationUploadField
          id="signup-biz-registration"
          file={bizRegistrationFile}
          onChange={setBizRegistrationFile}
        />

        {/* 가입 신청 실패 문구 자리. 높이를 고정해 문구가 떠도 버튼이 움직이지 않는다. */}
        <div className="mt-auto flex h-12 items-center justify-center">
          {errorMessage && (
            <p role="alert" className="text-center text-[13px] leading-[1.4] text-[#e5484d]">
              {errorMessage}
            </p>
          )}
        </div>

        <PrimaryButton type="submit" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupInfoPage;
