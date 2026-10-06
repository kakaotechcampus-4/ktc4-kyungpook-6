import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { isAxiosError } from "axios";
import { useNavigate } from "react-router-dom";
import { login } from "../services/auth";
import type { LoginRequest } from "../services/auth";
import { saveAuthToken } from "../services/authToken";

/*
  로그인 뒤·회원가입·계정 찾기를 누른 뒤 넘어갈 화면.
  점주 홈·가입·계정 찾기 화면이 아직 없어서 홈으로 둔다. 화면이 생기면 여기만 바꾼다.
*/
const AFTER_LOGIN_PATH = "/";
const SIGNUP_PATH = "/";
const FIND_ACCOUNT_PATH = "/";

/*
  서버 에러 type 의 끝부분(docs/에러_처리_가이드.md 의 "현재 나가는 type 값")별 화면 문구.
  서버 title 은 "이메일 또는 …" 처럼 화면 라벨(아이디)과 말투가 달라서 앱 문구로 바꿔 보여 준다.
*/
const LOGIN_ERROR_MESSAGES: Record<string, string> = {
  "invalid-credentials": "아이디 또는 비밀번호가 맞지 않아요.",
  // 로그인 요청에서 400 은 아이디가 이메일 형식이 아닐 때뿐이다(빈 칸은 버튼에서 막는다).
  "invalid-request": "아이디는 이메일 형식으로 입력해 주세요.",
  "owner-pending-approval": "관리자 승인을 기다리고 있어요. 승인되면 로그인할 수 있어요.",
  "owner-rejected": "가입이 승인되지 않은 계정이에요.",
};

/**
 * 로그인 실패를 화면 문구로 바꾼다.
 *
 * 에러 처리 가이드대로 type 으로 나눈다. 모르는 type 이면 서버 title 을,
 * 서버에 닿지 못했거나 title 도 없으면 기본 문구를 쓴다.
 */
export function toLoginErrorMessage(error: unknown): string {
  if (isAxiosError(error)) {
    if (!error.response) return "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";

    const { type, title } = (error.response.data ?? {}) as { type?: unknown; title?: unknown };
    const typeName = typeof type === "string" ? type.split("/").pop() : undefined;
    if (typeName && LOGIN_ERROR_MESSAGES[typeName]) return LOGIN_ERROR_MESSAGES[typeName];
    if (typeof title === "string" && title) return title;
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

  const canSubmit = email.trim() !== "" && password !== "" && !loginMutation.isPending;

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
