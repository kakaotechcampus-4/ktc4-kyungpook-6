import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import type { SignupStore } from "./signupDraft";
import type { OrderReceiveType } from "../services/order";
import { formatPhone, formatStorePhone } from "../utils/format";
import { validatePhone, validateStorePhone } from "../utils/signupValidation";

/** 가게 등록 1 다음 화면(영업시간). */
const NEXT_PATH = "/owner/signup/store/hours";

type CompanionLimit = SignupStore["companionLimit"];

export type UseSignupStoreResult = {
  storePhoto: File | null;
  setStorePhoto: (file: File | null) => void;
  storePhone: string;
  /** 입력하는 대로 053-123-4567 모양으로 하이픈을 넣는다. */
  setStorePhone: (value: string) => void;
  phone: string;
  /** 입력하는 대로 010-1234-5678 모양으로 하이픈을 넣는다. */
  setPhone: (value: string) => void;
  /** 함께 올 수 있는 인원. 아직 안 골랐으면 null. */
  companionLimit: CompanionLimit | null;
  setCompanionLimit: (value: CompanionLimit) => void;
  /** 고른 식사 방법. 둘 다 고를 수 있다. */
  receiveTypes: OrderReceiveType[];
  /** 식사 방법 하나를 켜고 끈다. */
  toggleReceiveType: (value: OrderReceiveType) => void;
  /** 두 전화번호가 맞고 인원·식사 방법을 골랐을 때만 true. 대표 사진은 선택이라 검사하지 않는다. */
  canProceed: boolean;
  goNext: () => void;
};

/** 가게 등록 1: 대표 사진·가게 전화번호·사장님 전화번호·함께 올 수 있는 인원·식사 방법. */
export const useSignupStore = (): UseSignupStoreResult => {
  const navigate = useNavigate();
  const { store, setStore } = useSignupDraft();

  // 선택이다. 고른 파일은 들고 있다가 가입 신청을 보낼 때 올린다(hooks/signupMenus.ts).
  const [storePhoto, setStorePhoto] = useState<File | null>(store?.storePhoto ?? null);
  const [storePhone, setStorePhone] = useState(store?.storePhone ?? "");
  const [phone, setPhone] = useState(store?.phone ?? "");
  const [companionLimit, setCompanionLimit] = useState<CompanionLimit | null>(store?.companionLimit ?? null);
  const [receiveTypes, setReceiveTypes] = useState<OrderReceiveType[]>(store?.receiveTypes ?? []);

  const canProceed =
    validateStorePhone(storePhone) === null &&
    validatePhone(phone) === null &&
    companionLimit !== null &&
    receiveTypes.length > 0;

  const goNext = () => {
    if (!canProceed) return;
    setStore({ storePhoto, storePhone, phone, companionLimit, receiveTypes });
    navigate(NEXT_PATH);
  };

  return {
    storePhoto,
    setStorePhoto,
    storePhone,
    setStorePhone: (value) => setStorePhone(formatStorePhone(value)),
    phone,
    setPhone: (value) => setPhone(formatPhone(value)),
    companionLimit,
    setCompanionLimit,
    receiveTypes,
    toggleReceiveType: (value) =>
      setReceiveTypes((prev) =>
        prev.includes(value) ? prev.filter((type) => type !== value) : [...prev, value],
      ),
    canProceed,
    goNext,
  };
};
