import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { login } from "../services/auth";
import type { LoginRequest } from "../services/auth";
import { saveAuthToken } from "../services/authToken";
import { toLoginErrorMessage } from "../utils/loginError";

/*
  로그인 뒤·회원가입·계정 찾기를 누른 뒤 넘어갈 화면.
  점주 홈·계정 찾기 화면이 아직 없어서 홈으로 둔다. 화면이 생기면 여기만 바꾼다.
*/
const AFTER_LOGIN_PATH = "/";
const SIGNUP_PATH = "/owner/signup";
const FIND_ACCOUNT_PATH = "/";

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
  /** 로그인 실패 문구. 실패하지 않았거나 실패 뒤 다시 입력하기 시작하면 null. */
  errorMessage: string | null;
  goSignup: () => void;
  goFindAccount: () => void;
};

export const useOwnerLogin = (): UseOwnerLoginResult => {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);

  // 토큰 저장까지 끝나야 성공으로 본다. 저장 전에 화면이 넘어가면 다음 요청에 토큰이 안 붙는다.
  const loginMutation = useMutation({
    mutationFn: async (payload: LoginRequest) => {
      const response = await login(payload);
      await saveAuthToken(response);
      return response;
    },
    onSuccess: () => navigate(AFTER_LOGIN_PATH, { replace: true }),
  });

  // 백엔드 LoginRequest 가 둘 다 @NotBlank 라서 공백만 있는 값도 빈 칸으로 본다.
  const canSubmit = email.trim() !== "" && password.trim() !== "" && !loginMutation.isPending;

  const submit = () => {
    if (!canSubmit) return;
    loginMutation.mutate({ email: email.trim(), password });
  };

  // 실패 문구는 다시 입력하기 시작하면 지운다. 고친 값에 지난 에러가 남아 있지 않게 한다.
  const clearErrorAnd = (setter: (value: string) => void) => (value: string) => {
    if (loginMutation.isError) loginMutation.reset();
    setter(value);
  };

  return {
    email,
    setEmail: clearErrorAnd(setEmail),
    password,
    setPassword: clearErrorAnd(setPassword),
    isPasswordVisible,
    togglePasswordVisible: () => setIsPasswordVisible((visible) => !visible),
    canSubmit,
    submit,
    errorMessage: loginMutation.error ? toLoginErrorMessage(loginMutation.error) : null,
    goSignup: () => navigate(SIGNUP_PATH),
    goFindAccount: () => navigate(FIND_ACCOUNT_PATH),
  };
};
