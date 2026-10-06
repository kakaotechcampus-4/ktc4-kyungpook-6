import type { FormEvent } from "react";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import PasswordVisibilityToggle from "../components/login/PasswordVisibilityToggle";
import LoginHelpLinks from "../components/login/LoginHelpLinks";
import { useOwnerLogin } from "../hooks/login";

function OwnerLoginPage() {
  const {
    email,
    setEmail,
    password,
    setPassword,
    isPasswordVisible,
    togglePasswordVisible,
    canSubmit,
    submit,
    errorMessage,
    goSignup,
    goFindAccount,
  } = useOwnerLogin();

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      <div className="flex pt-3 pb-5">
        <BackButton />
      </div>

      <h1 className="pb-4 text-[22px] leading-[1.4] font-bold text-[#1f2937]">사장님, 반가워요</h1>
      <p className="pb-8 text-sm leading-[normal] text-[#6b7280]">아이디와 비밀번호를 입력해주세요.</p>

      <form onSubmit={handleSubmit} className="flex flex-col">
        <TextField
          id="owner-login-email"
          label="아이디"
          className="pb-4"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          autoComplete="username"
          inputMode="email"
          autoCapitalize="none"
        />
        <TextField
          id="owner-login-password"
          label="비밀번호"
          type={isPasswordVisible ? "text" : "password"}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          trailing={
            <PasswordVisibilityToggle visible={isPasswordVisible} onToggle={togglePasswordVisible} />
          }
        />

        {/* 에러 문구 자리. 높이를 고정해 두어 문구가 떠도 버튼·링크가 밀리지 않는다. */}
        <div className="flex h-12 items-center justify-center">
          {errorMessage && (
            <p role="alert" className="text-center text-[13px] leading-[1.4] text-[#e5484d]">
              {errorMessage}
            </p>
          )}
        </div>

        <PrimaryButton type="submit" disabled={!canSubmit}>
          로그인
        </PrimaryButton>
      </form>

      <LoginHelpLinks onSignup={goSignup} onFindAccount={goFindAccount} />
    </main>
  );
}

export default OwnerLoginPage;
