import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import StorePhotoField from "../components/signup/StorePhotoField";
import StoreStepProgress from "../components/signup/StoreStepProgress";
import StoreSummaryCard from "../components/signup/StoreSummaryCard";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupStore } from "../hooks/signupStore";
import { STORE_ADDRESS_MAX_LENGTH, STORE_PHONE_MAX_LENGTH } from "../utils/signupValidation";

const LABEL_CLASS_NAME = "font-semibold";

/** 가게 등록 1: 상호명·주소(사업자등록증에서 읽은 값)·가게 전화번호·대표자 전화번호·대표 사진. */
function OwnerSignupStorePage() {
  const { account, bizCert, info } = useSignupDraft();
  const {
    summaryRows,
    editSummary,
    showStoreNameField,
    showStoreAddressField,
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    storePhone,
    setStorePhone,
    phone,
    setPhone,
    storePhoto,
    setStorePhoto,
    canProceed,
    goNext,
  } = useSignupStore();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !bizCert || !info) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="가게 등록" description="가게 정보를 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        {summaryRows.length > 0 && (
          <>
            <StoreSummaryCard rows={summaryRows} onEdit={editSummary} />
            <h2 className="pb-2.5 text-sm leading-[normal] font-bold text-[#1f2937]">직접 입력해 주세요</h2>
          </>
        )}

        {showStoreNameField && (
          <TextField
            id="signup-store-name"
            label="상호명"
            labelClassName={LABEL_CLASS_NAME}
            className="pb-3"
            value={storeName}
            onChange={(event) => setStoreName(event.target.value)}
            maxLength={200}
          />
        )}
        {showStoreAddressField && (
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
        )}
        <TextField
          id="signup-store-phone"
          label="가게 전화번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-3"
          placeholder="053-000-0000"
          value={storePhone}
          onChange={(event) => setStorePhone(event.target.value)}
          maxLength={STORE_PHONE_MAX_LENGTH}
          type="tel"
          inputMode="numeric"
        />
        <TextField
          id="signup-phone"
          label="대표자 전화번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-4"
          placeholder="010-0000-0000"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
          type="tel"
          inputMode="numeric"
          autoComplete="tel"
        />
        <StorePhotoField id="signup-store-photo" file={storePhoto} onChange={setStorePhoto} />

        <div className="flex-1 pb-6" />

        <StoreStepProgress current={1} />
        <PrimaryButton type="submit" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupStorePage;
