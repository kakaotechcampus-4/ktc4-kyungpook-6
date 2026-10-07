type LoginHelpLinksProps = {
  onSignup: () => void;
  onFindAccount: () => void;
};

const linkClassName =
  "border-b border-[#1f2937] py-px text-sm leading-[normal] font-medium text-[#1f2937]";

/** 로그인 버튼 아래의 "회원가입 | 계정 찾기". */
function LoginHelpLinks({ onSignup, onFindAccount }: LoginHelpLinksProps) {
  return (
    <div className="flex items-center justify-center gap-5 py-4">
      <button type="button" onClick={onSignup} className={linkClassName}>
        회원가입
      </button>
      <span aria-hidden="true" className="w-px self-stretch bg-[#f1f4f8]" />
      <button type="button" onClick={onFindAccount} className={linkClassName}>
        계정 찾기
      </button>
    </div>
  );
}

export default LoginHelpLinks;
