import { useState } from "react";
import { Outlet } from "react-router-dom";
import { SignupDraftContext } from "../hooks/signupDraft";
import type {
  SignupAccount,
  SignupBizCert,
  SignupBusinessHour,
  SignupInfo,
  SignupMenu,
  SignupStore,
  SignupStoreOptions,
} from "../hooks/signupDraft";

/** 회원가입 단계 화면들을 감싸 입력값을 함께 쓰게 한다. 화면은 그리지 않는다. */
function OwnerSignupLayout() {
  const [account, setAccount] = useState<SignupAccount | null>(null);
  const [bizCert, setBizCert] = useState<SignupBizCert | null>(null);
  const [info, setInfo] = useState<SignupInfo | null>(null);
  const [store, setStore] = useState<SignupStore | null>(null);
  const [storeOptions, setStoreOptions] = useState<SignupStoreOptions | null>(null);
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
        storeOptions,
        setStoreOptions,
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
          setStoreOptions(null);
          setBusinessHours(null);
          setMenus(null);
          setTakenEmail(null);
        },
      }}
    >
      <Outlet />
    </SignupDraftContext.Provider>
  );
}

export default OwnerSignupLayout;
