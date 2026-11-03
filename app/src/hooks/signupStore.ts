import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { formatPhone, formatStorePhone } from "../utils/format";
import { fillBlank } from "../utils/bizCertReading";
import {
  validatePhone,
  validateStoreAddress,
  validateStoreName,
  validateStorePhone,
} from "../utils/signupValidation";

/** 가게 등록 1 다음 화면(영업시간). */
const NEXT_PATH = "/owner/signup/store/hours";

/** 사업자등록증에서 읽어 요약 카드로 보여 주는 칸. */
export type StoreSummaryRow = { label: string; value: string };

export type UseSignupStoreResult = {
  /**
   * 사업자등록증에서 읽은 상호명·주소. 읽은 칸만 요약 카드에 보여 주고 입력 칸은 숨긴다.
   * "수정"을 누르거나 값이 틀리면 빈 목록이 되어 입력 칸으로 돌아간다.
   */
  summaryRows: StoreSummaryRow[];
  /** "수정". 요약 카드를 접고 상호명·주소를 입력 칸으로 보여 준다. */
  editSummary: () => void;
  /** 상호명 입력 칸을 보여 줄지. */
  showStoreNameField: boolean;
  /** 주소 입력 칸을 보여 줄지. */
  showStoreAddressField: boolean;
  storeName: string;
  setStoreName: (value: string) => void;
  storeAddress: string;
  setStoreAddress: (value: string) => void;
  storePhone: string;
  /** 입력하는 대로 053-123-4567 모양으로 하이픈을 넣는다. */
  setStorePhone: (value: string) => void;
  phone: string;
  /** 입력하는 대로 010-1234-5678 모양으로 하이픈을 넣는다. */
  setPhone: (value: string) => void;
  storePhoto: File | null;
  setStorePhoto: (file: File | null) => void;
  /** 네 칸이 모두 맞을 때만 true. 대표 사진은 선택이라 검사하지 않는다. */
  canProceed: boolean;
  goNext: () => void;
};

/** 가게 등록 1: 상호명·주소(사업자등록증에서 읽은 값)·가게 전화번호·대표자 전화번호·대표 사진. */
export const useSignupStore = (): UseSignupStoreResult => {
  const navigate = useNavigate();
  const { bizCert, store, setStore } = useSignupDraft();
  const reading = bizCert?.reading ?? null;
  // 법인 등록증은 상호(storeName)가 늘 비어 있어, 그때는 상호명만 입력 칸으로 남는다.
  const readStoreName = fillBlank("", reading?.storeName ?? null);
  const readStoreAddress = fillBlank("", reading?.storeAddress ?? null);

  const [storeName, setStoreName] = useState(store?.storeName ?? readStoreName);
  const [storeAddress, setStoreAddress] = useState(store?.storeAddress ?? readStoreAddress);
  const [storePhone, setStorePhone] = useState(store?.storePhone ?? "");
  const [phone, setPhone] = useState(store?.phone ?? "");
  // 선택이다. 고른 파일은 들고 있다가 가입 신청을 보낼 때 올린다(hooks/signupMenus.ts).
  const [storePhoto, setStorePhoto] = useState<File | null>(store?.storePhoto ?? null);
  const [editing, setEditing] = useState(false);

  // 읽은 값이 백엔드 규칙에 맞지 않으면(너무 길다 등) 요약으로 숨기지 않고 입력 칸으로 고치게 한다.
  const storeNameSummarized = !editing && readStoreName !== "" && validateStoreName(storeName) === null;
  const storeAddressSummarized =
    !editing && readStoreAddress !== "" && validateStoreAddress(storeAddress) === null;

  const summaryRows: StoreSummaryRow[] = [
    ...(storeNameSummarized ? [{ label: "상호명", value: storeName }] : []),
    ...(storeAddressSummarized ? [{ label: "주소", value: storeAddress }] : []),
  ];

  const canProceed = [
    validateStoreName(storeName),
    validateStoreAddress(storeAddress),
    validateStorePhone(storePhone),
    validatePhone(phone),
  ].every((message) => message === null);

  const goNext = () => {
    if (!canProceed) return;
    setStore({ storeName, storeAddress, storePhone, phone, storePhoto });
    navigate(NEXT_PATH);
  };

  return {
    summaryRows,
    editSummary: () => setEditing(true),
    showStoreNameField: !storeNameSummarized,
    showStoreAddressField: !storeAddressSummarized,
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    storePhone,
    setStorePhone: (value) => setStorePhone(formatStorePhone(value)),
    phone,
    setPhone: (value) => setPhone(formatPhone(value)),
    storePhoto,
    setStorePhoto,
    canProceed,
    goNext,
  };
};
