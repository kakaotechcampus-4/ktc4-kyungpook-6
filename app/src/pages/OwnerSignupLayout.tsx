import { useState } from "react";
import { Outlet } from "react-router-dom";
import ScrollIndicator from "../components/signup/ScrollIndicator";
import { SignupDraftContext } from "../hooks/signupDraft";
import type {
  SignupAccount,
  SignupBizCert,
  SignupBusinessHour,
  SignupInfo,
  SignupMenu,
  SignupStore,
} from "../hooks/signupDraft";

/** 회원가입 단계 화면들을 감싸 입력값을 함께 쓰게 한다. 겹쳐 그리는 스크롤 막대 말고는 화면을 그리지 않는다. */
function OwnerSignupLayout() {
  const [account, setAccount] = useState<SignupAccount | null>(null);
  const [bizCert, setBizCert] = useState<SignupBizCert | null>(null);
  const [info, setInfo] = useState<SignupInfo | null>(null);
  const [store, setStore] = useState<SignupStore | null>(null);
  const [businessHours, setBusinessHours] = useState<SignupBusinessHour[] | null>(null);
  const [menus, setMenus] = useState<SignupMenu[] | null>(null);
  const [takenEmail, setTakenEmail] = useState<string | null>(null);

  return (
    <SignupDraftContext.Provider
      value={{
        account,
        setAccount,
        bizCert,
        setBizCert,
        info,
        setInfo,
        store,
        setStore,
        businessHours,
        setBusinessHours,
        menus,
        setMenus,
        takenEmail,
        markEmailTaken: (email) => setTakenEmail(email.toLowerCase()),
        clear: () => {
          setAccount(null);
          setBizCert(null);
          setInfo(null);
          setStore(null);
          setBusinessHours(null);
          setMenus(null);
          setTakenEmail(null);
        },
      }}
    >
      <Outlet />
      <ScrollIndicator />
    </SignupDraftContext.Provider>
  );
}

export default OwnerSignupLayout;
