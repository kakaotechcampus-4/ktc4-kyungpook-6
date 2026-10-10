import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { signupOwner } from "../services/auth";
import type { OwnerSignupRequest } from "../services/auth";
import { saveStatusToken } from "../services/statusToken";
import { formatBizNo, formatPhone } from "../utils/format";
import { toSignupError } from "../utils/signupError";
import {
  validateBizNo,
  validatePhone,
  validateRepresentativeName,
  validateStoreName,
} from "../utils/signupValidation";

/** 가입 신청이 받아들여진 뒤 보여 줄 완료(승인 대기) 화면. */
const COMPLETE_PATH = "/owner/signup/complete";
/** 이미 가입된 이메일(409)일 때 돌아갈 1단계. */
const ACCOUNT_STEP_PATH = "/owner/signup";

export type UseSignupInfoResult = {
  representativeName: string;
  setRepresentativeName: (value: string) => void;
  phone: string;
  /** 입력하는 대로 010-1234-5678 모양으로 하이픈을 넣는다. */
  setPhone: (value: string) => void;
  storeName: string;
  setStoreName: (value: string) => void;
  bizNo: string;
  /** 입력하는 대로 XXX-XX-XXXXX 모양으로 하이픈을 넣는다. */
  setBizNo: (value: string) => void;
  bizRegistrationFile: File | null;
  setBizRegistrationFile: (file: File | null) => void;
  /**
   * 네 칸이 모두 맞고 요청 중이 아닐 때만 true. 칸별 에러 문구는 따로 보여 주지 않는다.
   * 사업자 등록증은 아직 서버가 받지 않아 검사하지 않는다(아래 TODO).
   */
  canProceed: boolean;
  /** "다음" 버튼. 1단계 값과 합쳐 가입 신청을 보낸다. */
  goNext: () => void;
  /** 가입 신청 실패 문구. 중복 이메일은 1단계로 돌아가므로 여기 오지 않는다. */
  errorMessage: string | null;
};

export const useSignupInfo = (): UseSignupInfoResult => {
  const navigate = useNavigate();
  const { account, info, setInfo, markEmailTaken } = useSignupDraft();

  // 중복 이메일(409)로 1단계에 다녀왔으면 적어 둔 값을 다시 채운다.
  const [representativeName, setRepresentativeName] = useState(info?.representativeName ?? "");
  const [phone, setPhone] = useState(info?.phone ?? "");
  const [storeName, setStoreName] = useState(info?.storeName ?? "");
  const [bizNo, setBizNo] = useState(info?.bizNo ?? "");
  /*
    TODO(백엔드 사업자 등록증 업로드 — 다음 주 예정): 지금은 고른 파일을 들고만 있고 서버에 보내지 않는다.
    업로드 API 가 생기면 가입 요청과 함께(또는 따로) 보내고, 필수라면 canProceed 조건에 넣는다.
  */
  const [bizRegistrationFile, setBizRegistrationFile] = useState<File | null>(
    info?.bizRegistrationFile ?? null,
  );

  // 가입 상태 확인용 토큰 저장까지 끝나야 성공으로 본다. 완료 화면이 이 토큰으로 승인 여부를 묻는다.
  const signupMutation = useMutation({
    mutationFn: async (payload: OwnerSignupRequest) => {
      const response = await signupOwner(payload);
      await saveStatusToken(response);
      return response;
    },
    onSuccess: () => navigate(COMPLETE_PATH, { replace: true }),
    onError: (error, payload) => {
      if (toSignupError(error).kind === "duplicate-email") {
        markEmailTaken(payload.email);
        navigate(ACCOUNT_STEP_PATH);
      }
    },
  });

  const isValid = [
    validateRepresentativeName(representativeName),
    validatePhone(phone),
    validateStoreName(storeName),
    validateBizNo(bizNo),
  ].every((message) => message === null);
  const canProceed = account !== null && isValid && !signupMutation.isPending;

  const goNext = () => {
    if (!canProceed || !account) return;
    setInfo({ representativeName, phone, storeName, bizNo, bizRegistrationFile });
    // 백엔드는 이름 앞뒤 공백을 지워 저장하고, bizNo·phone 의 하이픈은 지우고 저장한다. 보이는 그대로 보낸다.
    signupMutation.mutate({
      email: account.email,
      password: account.password,
      bizNo,
      storeName,
      representativeName,
      phone,
    });
  };

  // 다시 고치기 시작하면 지난 실패 문구를 지운다.
  const clearErrorAnd = (setter: (value: string) => void) => (value: string) => {
    if (signupMutation.isError) signupMutation.reset();
    setter(value);
  };

  const signupError = signupMutation.error ? toSignupError(signupMutation.error) : null;

  return {
    representativeName,
    setRepresentativeName: clearErrorAnd(setRepresentativeName),
    phone,
    setPhone: clearErrorAnd((value) => setPhone(formatPhone(value))),
    storeName,
    setStoreName: clearErrorAnd(setStoreName),
    bizNo,
    setBizNo: clearErrorAnd((value) => setBizNo(formatBizNo(value))),
    bizRegistrationFile,
    setBizRegistrationFile,
    canProceed,
    goNext,
    errorMessage: signupError?.kind === "message" ? signupError.message : null,
  };
};
