import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { saveAuthToken } from "../services/authToken";
import { authenticateChildCard } from "../services/childAuth";
import { toChildCardAuthErrorMessage } from "../utils/childCardAuthError";

/** 급식카드 번호 자릿수. */
export const CHILD_CARD_NUMBER_LENGTH = 16;

/** 인증한 뒤 넘어갈 아동 홈. */
const AFTER_AUTH_PATH = "/child/home";

export type UseChildCardLoginResult = {
  /** 입력한 카드 번호. 숫자만, 최대 16자리. */
  cardNumber: string;
  /** 숫자가 아닌 글자는 버리고 16자리까지만 받는다. */
  setCardNumber: (value: string) => void;
  /** 16자리가 다 찼고 요청 중이 아닐 때만 true. */
  canSubmit: boolean;
  submit: () => void;
  /** 인증 실패 문구. 다시 입력하기 시작하면 null. */
  errorMessage: string | null;
};

/**
 * 아동 급식카드 인증(POST /api/auth/children/card). 받은 토큰을 아동 토큰으로 저장하고 아동 홈으로 간다.
 * 다음에 앱을 열면 스플래시가 이 토큰을 보고 바로 아동 홈으로 보낸다(hooks/splash.ts).
 */
export const useChildCardLogin = (): UseChildCardLoginResult => {
  const navigate = useNavigate();
  const [cardNumber, setCardNumber] = useState("");

  // 토큰 저장까지 끝나야 성공으로 본다. 저장 전에 화면이 넘어가면 다음 요청에 토큰이 안 붙는다.
  const authMutation = useMutation({
    mutationFn: async (number: string) => {
      const response = await authenticateChildCard(number);
      await saveAuthToken(response, "CHILD");
    },
    onSuccess: () => navigate(AFTER_AUTH_PATH, { replace: true }),
  });

  const canSubmit = cardNumber.length === CHILD_CARD_NUMBER_LENGTH && !authMutation.isPending;

  return {
    cardNumber,
    setCardNumber: (value) => {
      if (authMutation.isError) authMutation.reset();
      setCardNumber(value.replace(/\D/g, "").slice(0, CHILD_CARD_NUMBER_LENGTH));
    },
    canSubmit,
    submit: () => {
      if (!canSubmit) return;
      authMutation.mutate(cardNumber);
    },
    errorMessage: authMutation.isError ? toChildCardAuthErrorMessage(authMutation.error) : null,
  };
};
