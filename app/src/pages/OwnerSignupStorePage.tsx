import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import StorePhotoField from "../components/signup/StorePhotoField";
import StoreStepProgress from "../components/signup/StoreStepProgress";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupStore } from "../hooks/signupStore";
import { STORE_ADDRESS_MAX_LENGTH, STORE_PHONE_MAX_LENGTH } from "../utils/signupValidation";

const LABEL_CLASS_NAME = "font-semibold";

/** 가게 등록 1: 상호명·주소·가게 전화번호·대표 사진. */
function OwnerSignupStorePage() {
  const { account, info } = useSignupDraft();
  const {
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    storePhone,
    setStorePhone,
    storePhoto,
    setStorePhoto,
    canProceed,
    goNext,
  } = useSignupStore();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !info) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="가게 등록" description="가게 정보를 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
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
          id="signup-store-phone"
          label="가게 전화번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-4"
          value={storePhone}
          onChange={(event) => setStorePhone(event.target.value)}
          maxLength={STORE_PHONE_MAX_LENGTH}
          type="tel"
          inputMode="numeric"
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
