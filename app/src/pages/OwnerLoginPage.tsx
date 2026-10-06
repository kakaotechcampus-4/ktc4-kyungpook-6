import type { FormEvent } from "react";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import PasswordVisibilityToggle from "../components/login/PasswordVisibilityToggle";
import SignupPromptCard from "../components/login/SignupPromptCard";
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
  } = useOwnerLogin();

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
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
          autoCapitalize="none"
        />
        <TextField
          id="owner-login-password"
          label="비밀번호"
          className="pb-4"
          type={isPasswordVisible ? "text" : "password"}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          trailing={
            <PasswordVisibilityToggle visible={isPasswordVisible} onToggle={togglePasswordVisible} />
          }
        />

        <PrimaryButton type="submit" className="mt-2" disabled={!canSubmit}>
          로그인
        </PrimaryButton>

        {errorMessage && (
          <p role="alert" className="pt-3 text-center text-sm leading-[normal] text-[#dc2626]">
            {errorMessage}
          </p>
        )}
      </form>

      <div className="mt-auto pt-6">
        <SignupPromptCard onSignup={goSignup} />
      </div>
    </main>
  );
}

export default OwnerLoginPage;
