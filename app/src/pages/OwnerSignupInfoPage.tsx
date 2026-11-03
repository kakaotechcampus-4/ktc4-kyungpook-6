import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import calendarIcon from "../assets/calendar-icon.svg";
import noticeCheckIcon from "../assets/notice-check-icon.svg";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import BusinessPairFields from "../components/signup/BusinessPairFields";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupInfo } from "../hooks/signupInfo";
import { BIZ_OPTIONAL_TEXT_MAX_LENGTH } from "../utils/signupValidation";

const LABEL_CLASS_NAME = "font-semibold";

/** 회원가입 3단계: 기본 정보. 사업자등록증에서 읽은 값으로 채워 두고 확인받는다. 상호명·주소는 가게 등록 1에서 받는다. */
function OwnerSignupInfoPage() {
  const { account, bizCert } = useSignupDraft();
  const {
    autofilled,
    representativeName,
    setRepresentativeName,
    corpName,
    setCorpName,
    bizNo,
    setBizNo,
    openDate,
    setOpenDate,
    businessPairs,
    changePair,
    canAddPair,
    addPair,
    removePair,
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
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
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
          id="signup-corp-name"
          label="법인명"
          labelHint="법인사업자만 입력"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          placeholder="(주)맛나푸드"
          value={corpName}
          onChange={(event) => setCorpName(event.target.value)}
          maxLength={BIZ_OPTIONAL_TEXT_MAX_LENGTH}
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
          id="signup-open-date"
          label="개업일"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          placeholder="YYYY.MM.DD"
          value={openDate}
          onChange={(event) => setOpenDate(event.target.value)}
          inputMode="numeric"
          trailing={<img src={calendarIcon} alt="" width={18} height={18} />}
        />
        <BusinessPairFields
          pairs={businessPairs}
          onChange={changePair}
          canAdd={canAddPair}
          onAdd={addPair}
          onRemove={removePair}
          labelClassName={LABEL_CLASS_NAME}
        />

        <div className="flex-1 pb-6" />

        <PrimaryButton type="submit" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupInfoPage;
