import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { formatBizNo, formatOpenDate, isoToOpenDate } from "../utils/format";
import { fillBlank } from "../utils/bizCertReading";
import {
  validateBizNo,
  validateOpenDate,
  validateRepresentativeName,
  validateStoreAddress,
  validateStoreName,
} from "../utils/signupValidation";

/** 기본 정보 다음 화면(가게 등록 1). */
const NEXT_PATH = "/owner/signup/store";

export type UseSignupInfoResult = {
  /** 이 화면 칸 중 하나라도 사업자등록증에서 읽었으면 true. "가져왔어요" 안내를 띄운다. */
  autofilled: boolean;
  representativeName: string;
  setRepresentativeName: (value: string) => void;
  bizNo: string;
  /** 입력하는 대로 XXX-XX-XXXXX 모양으로 하이픈을 넣는다. */
  setBizNo: (value: string) => void;
  storeName: string;
  setStoreName: (value: string) => void;
  storeAddress: string;
  setStoreAddress: (value: string) => void;
  openDate: string;
  /** 입력하는 대로 YYYY.MM.DD 모양으로 점을 넣는다. */
  setOpenDate: (value: string) => void;
  /** 대표자명·사업자등록번호·상호명·주소가 맞고, 개업일을 적었다면 맞을 때만 true. */
  canProceed: boolean;
  /** "다음" 버튼. 입력값을 보관하고 가게 등록으로 넘어간다. 가입 신청은 마지막 단계(메뉴)에서 보낸다. */
  goNext: () => void;
};

/** 기본 정보: 대표자명·사업자등록번호·상호명·주소·개업일. 처음에는 사업자등록증에서 읽은 값으로 채운다. */
export const useSignupInfo = (): UseSignupInfoResult => {
  const navigate = useNavigate();
  const { bizCert, info, setInfo } = useSignupDraft();
  const reading = bizCert?.reading ?? null;

  // 뒤 단계에서 돌아왔으면 적어 둔 값을, 처음이면 읽은 값을 채운다.
  const [representativeName, setRepresentativeName] = useState(
    () => info?.representativeName ?? fillBlank("", reading?.representativeName ?? null),
  );
  const [bizNo, setBizNo] = useState(() => info?.bizNo ?? formatBizNo(fillBlank("", reading?.bizNo ?? null)));
  // 법인 등록증은 상호(storeName)가 늘 비어 있어 상호명은 직접 적게 된다.
  const [storeName, setStoreName] = useState(() => info?.storeName ?? fillBlank("", reading?.storeName ?? null));
  const [storeAddress, setStoreAddress] = useState(
    () => info?.storeAddress ?? fillBlank("", reading?.storeAddress ?? null),
  );
  const [openDate, setOpenDate] = useState(
    () => info?.openDate ?? (reading?.openDate ? isoToOpenDate(reading.openDate) : ""),
  );

  const autofilled = Boolean(
    reading?.representativeName?.trim() ||
      reading?.bizNo?.trim() ||
      reading?.storeName?.trim() ||
      reading?.storeAddress?.trim() ||
      reading?.openDate,
  );

  const canProceed = [
    validateRepresentativeName(representativeName),
    validateBizNo(bizNo),
    validateStoreName(storeName),
    validateStoreAddress(storeAddress),
    validateOpenDate(openDate),
  ].every((message) => message === null);

  const goNext = () => {
    if (!canProceed) return;
    setInfo({ representativeName, bizNo, storeName, storeAddress, openDate });
    navigate(NEXT_PATH);
  };

  return {
    autofilled,
    representativeName,
    setRepresentativeName,
    bizNo,
    setBizNo: (value) => setBizNo(formatBizNo(value)),
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    openDate,
    setOpenDate: (value) => setOpenDate(formatOpenDate(value)),
    canProceed,
    goNext,
  };
};
