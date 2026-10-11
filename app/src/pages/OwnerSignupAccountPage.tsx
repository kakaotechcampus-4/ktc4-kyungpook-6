import type { FormEvent } from "react";
import FieldErrorSlot from "../components/ui/FieldErrorSlot";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import TextField from "../components/ui/TextField";
import PasswordVisibilityToggle from "../components/login/PasswordVisibilityToggle";
import { useSignupAccount } from "../hooks/signupAccount";
import { EMAIL_MAX_LENGTH, PASSWORD_MAX_LENGTH } from "../utils/signupValidation";

/** 회원가입 1단계: 아이디(이메일)·비밀번호. */
function OwnerSignupAccountPage() {
  const {
    email,
    setEmail,
    password,
    setPassword,
    passwordConfirm,
    setPasswordConfirm,
    touch,
    errors,
    isPasswordVisible,
    togglePasswordVisible,
    isPasswordConfirmVisible,
    togglePasswordConfirmVisible,
    canProceed,
    goNext,
  } = useSignupAccount();

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="회원가입" description="가입 요청 후 승인되면 사용할 수 있어요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <TextField
          id="signup-email"
          label="아이디"
          variant="outlined"
          placeholder="이메일 형식"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          onBlur={() => touch("email")}
          invalid={errors.email !== null}
          aria-describedby="signup-email-error"
          inputMode="email"
          autoComplete="username"
          autoCapitalize="none"
          maxLength={EMAIL_MAX_LENGTH}
        />
        <FieldErrorSlot id="signup-email-error" message={errors.email} />

        <TextField
          id="signup-password"
          label="비밀번호"
          variant="outlined"
          placeholder="8~72자로 입력해주세요"
          type={isPasswordVisible ? "text" : "password"}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          onBlur={() => touch("password")}
          invalid={errors.password !== null}
          aria-describedby="signup-password-error"
          autoComplete="new-password"
          maxLength={PASSWORD_MAX_LENGTH}
          trailing={
            <PasswordVisibilityToggle visible={isPasswordVisible} onToggle={togglePasswordVisible} />
          }
        />
        <FieldErrorSlot id="signup-password-error" message={errors.password} />

        <TextField
          id="signup-password-confirm"
          label="비밀번호 확인"
          variant="outlined"
          placeholder="비밀번호를 한 번 더 입력해주세요"
          type={isPasswordConfirmVisible ? "text" : "password"}
          value={passwordConfirm}
          onChange={(event) => setPasswordConfirm(event.target.value)}
          onBlur={() => touch("passwordConfirm")}
          invalid={errors.passwordConfirm !== null}
          aria-describedby="signup-password-confirm-error"
          autoComplete="new-password"
          maxLength={PASSWORD_MAX_LENGTH}
          trailing={
            <PasswordVisibilityToggle
              visible={isPasswordConfirmVisible}
              onToggle={togglePasswordConfirmVisible}
            />
          }
        />
        <FieldErrorSlot id="signup-password-confirm-error" message={errors.passwordConfirm} />

        <PrimaryButton type="submit" className="mt-auto" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupAccountPage;
