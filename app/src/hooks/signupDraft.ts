import { createContext, useContext } from "react";
import type { BusinessDay } from "../services/auth";

/** 1단계(계정)에서 받은 값. */
export type SignupAccount = {
  email: string;
  password: string;
};

/** 2단계(기본 정보)에서 받은 값. 앞 단계로 돌아갔다 와도 다시 채우려고 보관한다. */
export type SignupInfo = {
  representativeName: string;
  phone: string;
  bizNo: string;
  bizRegistrationFile: File | null;
};

/** 가게 등록 1(가게 정보)에서 받은 값. */
export type SignupStore = {
  storeName: string;
  storeAddress: string;
  storePhone: string;
  /** 대표 사진(선택). 가입 신청을 보낼 때 올린다(hooks/signupMenus.ts). */
  storePhoto: File | null;
};

/** 가게 등록 2(영업시간)의 한 줄. 요일을 아직 고르지 않았으면 day 는 "". */
export type SignupBusinessHour = {
  /** 화면에서 줄을 구분하는 키. 서버로 보내지 않는다. */
  id: string;
  day: BusinessDay | "";
  /** "HH:mm" (utils/businessHours.ts 목록 중 하나). 자정에 닫으면 close 는 "24:00". 아직 안 골랐으면 "". */
  open: string;
  close: string;
};

/** 가게 등록 3(메뉴)의 메뉴 한 개. */
export type SignupMenu = {
  /** 화면에서 메뉴를 구분하는 키. 서버로 보내지 않는다. */
  id: string;
  name: string;
  /** 메뉴 사진(선택). 가입 신청을 보낼 때 올린다(hooks/signupMenus.ts). */
  photo: File | null;
};

/** 회원가입 단계들이 함께 쓰는 입력값. 마지막 단계(메뉴)에서 모아 가입 요청을 보낸다. */
export type SignupDraft = {
  account: SignupAccount | null;
  setAccount: (account: SignupAccount) => void;
  info: SignupInfo | null;
  setInfo: (info: SignupInfo) => void;
  store: SignupStore | null;
  setStore: (store: SignupStore) => void;
  businessHours: SignupBusinessHour[] | null;
  setBusinessHours: (businessHours: SignupBusinessHour[]) => void;
  menus: SignupMenu[] | null;
  setMenus: (menus: SignupMenu[]) => void;
  /**
   * 가입 요청에서 409 duplicate-email 을 받은 이메일. 1단계로 돌아가 아이디 칸에 중복 문구를 띄운다.
   * 서버는 이메일을 소문자로 맞춰 비교하므로 소문자로 둔다.
   */
  takenEmail: string | null;
  markEmailTaken: (email: string) => void;
  /** 가입 신청이 끝나면 부른다. 비밀번호를 포함한 입력값을 메모리에서 지운다. */
  clear: () => void;
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

let lastDraftItemId = 0;

/**
 * 영업시간 줄·메뉴에 붙이는 화면용 키. crypto.randomUUID 는 보안 컨텍스트에서만 있어
 * (휴대폰으로 http://192.168.x.x 개발 서버에 붙으면 없다) 숫자를 센다.
 */
export const newDraftItemId = () => String(++lastDraftItemId);
