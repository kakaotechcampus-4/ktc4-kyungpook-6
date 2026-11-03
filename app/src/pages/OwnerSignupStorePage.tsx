import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import ChoiceChip from "../components/signup/ChoiceChip";
import StorePhotoField from "../components/signup/StorePhotoField";
import StoreStepProgress from "../components/signup/StoreStepProgress";
import { useSignupDraft } from "../hooks/signupDraft";
import type { SignupStore } from "../hooks/signupDraft";
import { useSignupStore } from "../hooks/signupStore";
import type { OrderReceiveType } from "../services/order";
import { STORE_PHONE_MAX_LENGTH } from "../utils/signupValidation";
import SignupBottomBar from "../components/signup/SignupBottomBar";

const LABEL_CLASS_NAME = "font-semibold";
/* 제목 옆 설명은 짧으면 같은 줄에, 길면 다음 줄로 넘어간다. */
const SECTION_HEADER_CLASS_NAME = "flex flex-wrap items-baseline gap-x-1.5 gap-y-1 pb-3";
const SECTION_TITLE_CLASS_NAME = "text-sm leading-[normal] font-semibold text-[#6b7280]";
const SECTION_DESCRIPTION_CLASS_NAME = "text-xs leading-[normal] text-[#a9afbb]";

const COMPANION_CHOICES: { value: SignupStore["companionLimit"]; label: string }[] = [
  { value: 0, label: "아이 혼자만" },
  { value: 1, label: "1명까지 함께" },
  { value: 2, label: "2명까지 함께" },
];

const RECEIVE_TYPE_CHOICES: { value: OrderReceiveType; label: string }[] = [
  { value: "TAKEOUT", label: "포장해 가기" },
  { value: "DINE_IN", label: "매장에서 이용하기" },
];

/** 가게 등록 1: 대표 사진·가게 전화번호·사장님 전화번호·아이와 함께 올 수 있는 인원·아이가 이용할 수 있는 방법. */
function OwnerSignupStorePage() {
  const { account, bizCert, info } = useSignupDraft();
  const {
    storePhoto,
    setStorePhoto,
    storePhone,
    setStorePhone,
    phone,
    setPhone,
    companionLimit,
    setCompanionLimit,
    receiveTypes,
    toggleReceiveType,
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
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)]">
      <LogoHeader title="가게 등록" description="가게 세부 정보를 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <div className="pb-4">
          <StorePhotoField id="signup-store-photo" file={storePhoto} onChange={setStorePhoto} />
        </div>
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
          label="사장님 전화번호"
          labelClassName={LABEL_CLASS_NAME}
          className="pb-4"
          placeholder="010-0000-0000"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
          type="tel"
          inputMode="numeric"
          autoComplete="tel"
        />

        <section className="pb-4">
          <div className={SECTION_HEADER_CLASS_NAME}>
            <h2 className={SECTION_TITLE_CLASS_NAME}>아이와 함께 올 수 있는 인원</h2>
            <p className={SECTION_DESCRIPTION_CLASS_NAME}>아이와 같이 와서 함께 이용할 수 있는 인원을 골라 주세요.</p>
          </div>
          <div className="flex gap-2">
            {COMPANION_CHOICES.map(({ value, label }) => (
              <ChoiceChip
                key={value}
                label={label}
                selected={companionLimit === value}
                onClick={() => setCompanionLimit(value)}
              />
            ))}
          </div>
        </section>

        <section>
          <div className={SECTION_HEADER_CLASS_NAME}>
            <h2 className={SECTION_TITLE_CLASS_NAME}>아이가 이용할 수 있는 방법</h2>
            <p className={SECTION_DESCRIPTION_CLASS_NAME}>여러 개 고를 수 있어요.</p>
          </div>
          <div className="flex gap-2">
            {RECEIVE_TYPE_CHOICES.map(({ value, label }) => (
              <ChoiceChip
                key={value}
                label={label}
                selected={receiveTypes.includes(value)}
                onClick={() => toggleReceiveType(value)}
              />
            ))}
          </div>
        </section>

        <SignupBottomBar className="pt-6">
          <StoreStepProgress current={1} />
          <PrimaryButton type="submit" disabled={!canProceed}>
            다음
          </PrimaryButton>
        </SignupBottomBar>
      </form>
    </main>
  );
}

export default OwnerSignupStorePage;
