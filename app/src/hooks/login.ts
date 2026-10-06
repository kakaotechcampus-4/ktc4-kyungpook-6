import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { isAxiosError } from "axios";
import { useNavigate } from "react-router-dom";
import { login } from "../services/auth";

/*
  로그인 뒤·회원가입을 누른 뒤 넘어갈 화면.
  점주 홈·가입 화면이 아직 없어서 홈으로 둔다. 화면이 생기면 여기만 바꾼다.
*/
const AFTER_LOGIN_PATH = "/";
const SIGNUP_PATH = "/";

/**
 * 로그인 실패를 화면 문구로 바꾼다.
 *
 * 에러 처리 가이드대로 서버가 준 title(한글)을 그대로 보여 준다.
 * 서버에 닿지 못했거나 title 이 없는 응답이면 기본 문구를 쓴다.
 */
export function toLoginErrorMessage(error: unknown): string {
  if (isAxiosError(error)) {
    const title: unknown = error.response?.data?.title;
    if (typeof title === "string" && title) return title;
    if (!error.response) return "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
  }
  return "로그인하지 못했어요. 잠시 후 다시 시도해 주세요.";
}

export type UseOwnerLoginResult = {
  email: string;
  setEmail: (value: string) => void;
  password: string;
  setPassword: (value: string) => void;
  /** 눈 아이콘으로 비밀번호를 보이거나 가린다. */
  isPasswordVisible: boolean;
  togglePasswordVisible: () => void;
  /** 두 칸이 다 차 있고 요청 중이 아닐 때만 true. */
  canSubmit: boolean;
  submit: () => void;
  /** 로그인 실패 문구. 실패하지 않았으면 null. */
  errorMessage: string | null;
  goSignup: () => void;
};

export const useOwnerLogin = (): UseOwnerLoginResult => {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);

  // TODO: 받은 토큰을 어디에 저장할지(@capacitor/preferences 등) 정해지면 onSuccess 에서 저장한다.
  const loginMutation = useMutation({
    mutationFn: login,
    onSuccess: () => navigate(AFTER_LOGIN_PATH, { replace: true }),
  });

  const canSubmit = email.trim() !== "" && password !== "" && !loginMutation.isPending;

  const submit = () => {
    if (!canSubmit) return;
    loginMutation.mutate({ email: email.trim(), password });
  };

  return {
    email,
    setEmail,
    password,
    setPassword,
    isPasswordVisible,
    togglePasswordVisible: () => setIsPasswordVisible((visible) => !visible),
    canSubmit,
    submit,
    errorMessage: loginMutation.error ? toLoginErrorMessage(loginMutation.error) : null,
    goSignup: () => navigate(SIGNUP_PATH),
  };
};
