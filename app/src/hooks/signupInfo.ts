import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { formatBizNo, formatPhone } from "../utils/format";
import { validateBizNo, validatePhone, validateRepresentativeName } from "../utils/signupValidation";

/** 기본 정보 다음 화면(가게 등록 1). */
const NEXT_PATH = "/owner/signup/store";

export type UseSignupInfoResult = {
  representativeName: string;
  setRepresentativeName: (value: string) => void;
  phone: string;
  /** 입력하는 대로 010-1234-5678 모양으로 하이픈을 넣는다. */
  setPhone: (value: string) => void;
  bizNo: string;
  /** 입력하는 대로 XXX-XX-XXXXX 모양으로 하이픈을 넣는다. */
  setBizNo: (value: string) => void;
  bizRegistrationFile: File | null;
  setBizRegistrationFile: (file: File | null) => void;
  /**
   * 세 칸이 모두 맞을 때만 true. 칸별 에러 문구는 따로 보여 주지 않는다.
   * 사업자 등록증은 아직 서버가 받지 않아 검사하지 않는다(아래 TODO).
   */
  canProceed: boolean;
  /** "다음" 버튼. 입력값을 보관하고 가게 등록으로 넘어간다. 가입 신청은 마지막 단계(메뉴)에서 보낸다. */
  goNext: () => void;
};

export const useSignupInfo = (): UseSignupInfoResult => {
  const navigate = useNavigate();
  const { info, setInfo } = useSignupDraft();

  // 뒤 단계에서 돌아왔거나 중복 이메일(409)로 1단계에 다녀왔으면 적어 둔 값을 다시 채운다.
  const [representativeName, setRepresentativeName] = useState(info?.representativeName ?? "");
  const [phone, setPhone] = useState(info?.phone ?? "");
  const [bizNo, setBizNo] = useState(info?.bizNo ?? "");
  /*
    TODO(백엔드 사업자 등록증 업로드 — 다음 주 예정): 지금은 고른 파일을 들고만 있고 서버에 보내지 않는다.
    업로드 API 가 생기면 가입 요청과 함께(또는 따로) 보내고, 필수라면 canProceed 조건에 넣는다.
  */
  const [bizRegistrationFile, setBizRegistrationFile] = useState<File | null>(
    info?.bizRegistrationFile ?? null,
  );

  const canProceed = [
    validateRepresentativeName(representativeName),
    validatePhone(phone),
    validateBizNo(bizNo),
  ].every((message) => message === null);

  const goNext = () => {
    if (!canProceed) return;
    setInfo({ representativeName, phone, bizNo, bizRegistrationFile });
    navigate(NEXT_PATH);
  };

  return {
    representativeName,
    setRepresentativeName,
    phone,
    setPhone: (value) => setPhone(formatPhone(value)),
    bizNo,
    setBizNo: (value) => setBizNo(formatBizNo(value)),
    bizRegistrationFile,
    setBizRegistrationFile,
    canProceed,
    goNext,
  };
};
