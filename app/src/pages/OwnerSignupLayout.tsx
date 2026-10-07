import { useState } from "react";
import { Outlet } from "react-router-dom";
import { SignupDraftContext } from "../hooks/signupDraft";
import type { SignupAccount, SignupInfo } from "../hooks/signupDraft";

/** 회원가입 단계 화면들을 감싸 입력값을 함께 쓰게 한다. 화면은 그리지 않는다. */
function OwnerSignupLayout() {
  const [account, setAccount] = useState<SignupAccount | null>(null);
  const [info, setInfo] = useState<SignupInfo | null>(null);
  const [takenEmail, setTakenEmail] = useState<string | null>(null);

  return (
    <SignupDraftContext.Provider
      value={{
        account,
        setAccount,
        info,
        setInfo,
        takenEmail,
        markEmailTaken: (email) => setTakenEmail(email.toLowerCase()),
      }}
    >
      <Outlet />
    </SignupDraftContext.Provider>
  );
}

export default OwnerSignupLayout;
