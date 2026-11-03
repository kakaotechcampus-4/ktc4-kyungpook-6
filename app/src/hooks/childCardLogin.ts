import { useState } from "react";
import { useNavigate } from "react-router-dom";

/** 급식카드 번호 자릿수. */
export const CHILD_CARD_NUMBER_LENGTH = 16;

/*
  인증한 뒤 넘어갈 아동 홈. 화면은 다음 작업에서 App.tsx 에 같은 주소로 붙인다.
  카드 인증 API 가 아직 없어서, 인증하기를 누르면 서버를 부르지 않고 바로 넘긴다. API 가 생기면 submit 만 바꾼다.
*/
const AFTER_AUTH_PATH = "/child/home";

export type UseChildCardLoginResult = {
  /** 입력한 카드 번호. 숫자만, 최대 16자리. */
  cardNumber: string;
  /** 숫자가 아닌 글자는 버리고 16자리까지만 받는다. */
  setCardNumber: (value: string) => void;
  /** 16자리가 다 찼을 때만 true. */
  canSubmit: boolean;
  submit: () => void;
};

export const useChildCardLogin = (): UseChildCardLoginResult => {
  const navigate = useNavigate();
  const [cardNumber, setCardNumber] = useState("");

  const canSubmit = cardNumber.length === CHILD_CARD_NUMBER_LENGTH;

  const submit = () => {
    if (!canSubmit) return;
    navigate(AFTER_AUTH_PATH, { replace: true });
  };

  return {
    cardNumber,
    setCardNumber: (value) => setCardNumber(value.replace(/\D/g, "").slice(0, CHILD_CARD_NUMBER_LENGTH)),
    canSubmit,
    submit,
  };
};
