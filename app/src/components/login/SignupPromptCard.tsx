type SignupPromptCardProps = {
  onSignup: () => void;
};

/** 로그인 화면 아래의 "아직 계정이 없나요?" 카드. */
function SignupPromptCard({ onSignup }: SignupPromptCardProps) {
  return (
    <div className="flex items-center justify-between rounded-2xl border border-[#e5e7eb] bg-white p-4">
      <div className="flex flex-col gap-0.5 pt-px">
        <p className="text-[15px] leading-[normal] font-bold text-[#1f2937]">아직 계정이 없나요?</p>
        <p className="text-xs leading-[normal] text-[#6b7280]">가입 요청 후 승인되면 사용할 수 있어요</p>
      </div>
      <button
        type="button"
        onClick={onSignup}
        className="flex h-10 w-20 shrink-0 items-center justify-center rounded-[10px] border border-[#e5e7eb] bg-white text-sm leading-[normal] font-semibold text-[#1f2937]"
      >
        회원가입
      </button>
    </div>
  );
}

export default SignupPromptCard;
