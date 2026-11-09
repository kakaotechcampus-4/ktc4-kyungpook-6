import type { FormEvent } from "react";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import ChildCardPreview from "../components/child/ChildCardPreview";
import { useChildCardLogin } from "../hooks/childCardLogin";

/** 입력 칸에는 4자리마다 띄어 보여준다. 상태에는 숫자만 둔다. */
const formatCardNumber = (cardNumber: string) => cardNumber.replace(/(\d{4})(?=\d)/g, "$1 ");

/** 아동이 역할 선택에서 "식사하러 왔어요"를 고르면 오는 급식카드 인증 화면. */
function ChildCardLoginPage() {
  const { cardNumber, setCardNumber, canSubmit, submit, errorMessage } = useChildCardLogin();

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white pt-[env(safe-area-inset-top)]">
      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <div className="flex h-12 items-center px-5">
          <BackButton />
        </div>

        <div className="flex flex-1 flex-col px-5">
          <h1 className="pt-2 text-[22px] leading-[1.4] font-bold text-[#1f2937]">
            급식카드로
            <br />
            시작해요
          </h1>
          <p className="pt-2 pb-7 text-[13px] leading-[normal] text-[#6b7280]">
            카드 번호로 한 번만 인증하면 돼요.
          </p>

          <ChildCardPreview cardNumber={cardNumber} />

          <label htmlFor="child-card-number" className="block pt-7 pb-2 text-xs leading-[normal] text-[#6b7280]">
            카드 번호
          </label>
          <input
            id="child-card-number"
            className="h-12 w-full rounded-[10px] bg-[#f1f3f6] px-3.5 text-[15px] leading-[normal] text-[#1f2937] outline-none placeholder:text-[#a9afbb]"
            placeholder="카드 번호 16자리"
            value={formatCardNumber(cardNumber)}
            onChange={(event) => setCardNumber(event.target.value)}
            inputMode="numeric"
            autoComplete="off"
            // 16자리 + 띄어쓰기 3칸
            maxLength={19}
          />
          {errorMessage && (
            <p role="alert" className="pt-2 text-[13px] leading-[normal] text-[#e5484d]">
              {errorMessage}
            </p>
          )}
        </div>

        <div className="px-5 pt-3 pb-[calc(30px+env(safe-area-inset-bottom))]">
          <PrimaryButton type="submit" disabled={!canSubmit}>
            인증하기
          </PrimaryButton>
        </div>
      </form>
    </main>
  );
}

export default ChildCardLoginPage;
