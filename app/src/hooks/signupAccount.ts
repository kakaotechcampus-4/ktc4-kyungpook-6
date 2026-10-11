import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { checkEmailAvailable } from "../services/auth";
import {
  DUPLICATE_EMAIL_MESSAGE,
  validateEmail,
  validatePassword,
  validatePasswordConfirm,
} from "../utils/signupValidation";

type AccountField = "email" | "password" | "passwordConfirm";

/** 계정 단계 다음 화면(기본 정보). */
const NEXT_PATH = "/owner/signup/info";

export type UseSignupAccountResult = {
  email: string;
  setEmail: (value: string) => void;
  password: string;
  setPassword: (value: string) => void;
  passwordConfirm: string;
  setPasswordConfirm: (value: string) => void;
  /** 칸을 벗어날 때 부른다. 한 번 벗어난 칸부터 에러를 보여 준다. */
  touch: (field: AccountField) => void;
  /** 칸별 에러 문구. 아직 건드리지 않았거나 맞으면 null. */
  errors: Record<AccountField, string | null>;
  isPasswordVisible: boolean;
  togglePasswordVisible: () => void;
  isPasswordConfirmVisible: boolean;
  togglePasswordConfirmVisible: () => void;
  /** 세 칸이 모두 맞고, 이메일 중복 확인까지 통과했을 때만 true. */
  canProceed: boolean;
  goNext: () => void;
};

export const useSignupAccount = (): UseSignupAccountResult => {
  const navigate = useNavigate();
  const { account, setAccount, takenEmail } = useSignupDraft();

  // 기본 정보 화면에서 뒤로 왔을 때는 적어 둔 값을 다시 채운다.
  const [email, setEmail] = useState(account?.email ?? "");
  const [password, setPassword] = useState(account?.password ?? "");
  const [passwordConfirm, setPasswordConfirm] = useState(account?.password ?? "");
  // 가입 요청이 중복(409)으로 돌아왔으면 아이디 칸 문구를 바로 보여 준다.
  const [touched, setTouched] = useState<Set<AccountField>>(
    () => new Set(takenEmail ? ["email"] : []),
  );
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isPasswordConfirmVisible, setIsPasswordConfirmVisible] = useState(false);

  const trimmedEmail = email.trim();
  const emailFormatError = validateEmail(email);

  /*
    이메일 중복 확인. 형식이 맞는 이메일만 묻고, 같은 이메일은 다시 묻지 않는다(react-query 캐시).
    서버 API 가 아직 없어 services/auth.ts 의 checkEmailAvailable 은 임시로 항상 true 다(TODO 참고).
  */
  const availability = useQuery({
    queryKey: ["signup", "email-available", trimmedEmail],
    queryFn: () => checkEmailAvailable(trimmedEmail),
    enabled: emailFormatError === null,
    staleTime: Infinity,
  });
  // 중복 확인 API 결과, 또는 가입 요청에서 409 를 받은 이메일이면 중복이다.
  const isEmailTaken =
    availability.data === false || (takenEmail !== null && trimmedEmail.toLowerCase() === takenEmail);

  // 형식이 틀리면 형식 문구, 형식은 맞는데 이미 가입된 이메일이면 중복 문구.
  const emailError = emailFormatError ?? (isEmailTaken ? DUPLICATE_EMAIL_MESSAGE : null);

  const validation: Record<AccountField, string | null> = {
    email: emailError,
    password: validatePassword(password),
    passwordConfirm: validatePasswordConfirm(password, passwordConfirm),
  };
  const shown = (field: AccountField) => (touched.has(field) ? validation[field] : null);

  // 중복 확인 응답을 받기 전(또는 실패)에는 넘어가지 않는다.
  const canProceed =
    Object.values(validation).every((message) => message === null) && availability.data === true;

  const touch = (field: AccountField) => {
    setTouched((prev) => (prev.has(field) ? prev : new Set(prev).add(field)));
  };

  const goNext = () => {
    if (!canProceed) return;
    setAccount({ email: trimmedEmail, password });
    navigate(NEXT_PATH);
  };

  return {
    email,
    setEmail,
    password,
    setPassword,
    passwordConfirm,
    setPasswordConfirm,
    touch,
    errors: {
      email: shown("email"),
      password: shown("password"),
      passwordConfirm: shown("passwordConfirm"),
    },
    isPasswordVisible,
    togglePasswordVisible: () => setIsPasswordVisible((visible) => !visible),
    isPasswordConfirmVisible,
    togglePasswordConfirmVisible: () => setIsPasswordConfirmVisible((visible) => !visible),
    canProceed,
    goNext,
  };
};
