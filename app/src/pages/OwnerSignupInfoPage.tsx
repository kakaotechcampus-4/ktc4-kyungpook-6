import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import calendarIcon from "../assets/calendar-icon.svg";
import noticeCheckIcon from "../assets/notice-check-icon.svg";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupInfo } from "../hooks/signupInfo";
import { STORE_ADDRESS_MAX_LENGTH } from "../utils/signupValidation";
import SignupBottomBar from "../components/signup/SignupBottomBar";

const LABEL_CLASS_NAME = "font-semibold";

/** 회원가입 3단계: 기본 정보. 사업자등록증에서 읽은 값으로 채워 두고 확인받는다. */
function OwnerSignupInfoPage() {
  const { account, bizCert } = useSignupDraft();
  const {
    autofilled,
    representativeName,
    setRepresentativeName,
    bizNo,
    setBizNo,
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    openDate,
    setOpenDate,
    canProceed,
    goNext,
  } = useSignupInfo();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !bizCert) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)]">
      <LogoHeader title="기본 정보" description="기본 정보를 알려주세요." />

      {autofilled && (
        <p className="mb-5 flex items-center gap-2 rounded-xl bg-[#fbf3dc] px-3.5 py-[11px] text-[13px] leading-4 font-medium text-[#8a6a12]">
          <img src={noticeCheckIcon} alt="" width={16} height={16} />
          사업자등록증에서 가져왔어요. 확인해 주세요.
        </p>
      )}

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
          id="signup-biz-no"
          label="사업자등록번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          value={bizNo}
          onChange={(event) => setBizNo(event.target.value)}
          inputMode="numeric"
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
          id="signup-store-address"
          label="주소"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          value={storeAddress}
          onChange={(event) => setStoreAddress(event.target.value)}
          maxLength={STORE_ADDRESS_MAX_LENGTH}
          autoComplete="street-address"
        />
        <TextField
          id="signup-open-date"
          label="개업일"
          labelClassName={LABEL_CLASS_NAME}
          placeholder="YYYY.MM.DD"
          value={openDate}
          onChange={(event) => setOpenDate(event.target.value)}
          inputMode="numeric"
          trailing={<img src={calendarIcon} alt="" width={18} height={18} />}
        />

        <SignupBottomBar className="pt-6">
          <PrimaryButton type="submit" disabled={!canProceed}>
            다음
          </PrimaryButton>
        </SignupBottomBar>
      </form>
    </main>
  );
}

export default OwnerSignupInfoPage;
