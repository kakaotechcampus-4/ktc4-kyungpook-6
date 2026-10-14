import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import addTimeIcon from "../assets/add-time-icon.svg";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import BusinessHourRow from "../components/signup/BusinessHourRow";
import StoreStepProgress from "../components/signup/StoreStepProgress";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupHours } from "../hooks/signupHours";

/** 가게 등록 2: 영업시간. */
function OwnerSignupHoursPage() {
  const { account, info, store } = useSignupDraft();
  const { lines, changeLine, canAddLine, addLine, removeLine, canProceed, goNext } = useSignupHours();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !info || !store) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="가게 등록" description="영업시간을 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <div className="flex items-center justify-between pb-3">
          <h2 className="text-sm leading-[normal] font-semibold text-[#6b7280]">영업시간</h2>
          <button
            type="button"
            onClick={addLine}
            disabled={!canAddLine}
            className="flex h-8 items-center gap-1 rounded-full bg-[#fbf3dc] pr-3 pl-[9px] text-[13px] leading-[normal] font-semibold text-[#b8891a] disabled:opacity-40"
          >
            <img src={addTimeIcon} alt="" width={16} height={16} />
            시간 추가
          </button>
        </div>

        {lines.map((line) => (
          <BusinessHourRow
            key={line.id}
            line={line}
            onChange={(field, value) => changeLine(line.id, field, value)}
            onRemove={() => removeLine(line.id)}
          />
        ))}

        <div className="flex-1 pb-6" />

        <StoreStepProgress current={2} />
        <PrimaryButton type="submit" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupHoursPage;
