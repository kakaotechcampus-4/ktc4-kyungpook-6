import { createContext, useContext } from "react";

/** 1단계(계정)에서 받은 값. */
export type SignupAccount = {
  email: string;
  password: string;
};

/** 2단계(기본 정보)에서 받은 값. 1단계로 돌아갔다 와도 다시 채우려고 보관한다. */
export type SignupInfo = {
  representativeName: string;
  phone: string;
  storeName: string;
  bizNo: string;
  bizRegistrationFile: File | null;
};

/** 회원가입 단계들이 함께 쓰는 입력값. 마지막 단계에서 모아 가입 요청을 보낸다. */
export type SignupDraft = {
  account: SignupAccount | null;
  setAccount: (account: SignupAccount) => void;
  info: SignupInfo | null;
  setInfo: (info: SignupInfo) => void;
  /**
   * 가입 요청에서 409 duplicate-email 을 받은 이메일. 1단계로 돌아가 아이디 칸에 중복 문구를 띄운다.
   * 서버는 이메일을 소문자로 맞춰 비교하므로 소문자로 둔다.
   */
  takenEmail: string | null;
  markEmailTaken: (email: string) => void;
};

export const SignupDraftContext = createContext<SignupDraft | null>(null);

/** OwnerSignupLayout 아래에서만 쓴다. */
export const useSignupDraft = (): SignupDraft => {
  const draft = useContext(SignupDraftContext);
  if (!draft) {
    throw new Error("useSignupDraft 는 OwnerSignupLayout 안에서만 쓸 수 있다");
  }
  return draft;
};
