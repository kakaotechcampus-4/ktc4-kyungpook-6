import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { formatStorePhone } from "../utils/format";
import {
  validateStoreAddress,
  validateStoreName,
  validateStorePhone,
} from "../utils/signupValidation";

/** 가게 등록 1 다음 화면(영업시간). */
const NEXT_PATH = "/owner/signup/store/hours";

export type UseSignupStoreResult = {
  storeName: string;
  setStoreName: (value: string) => void;
  storeAddress: string;
  setStoreAddress: (value: string) => void;
  storePhone: string;
  /** 입력하는 대로 053-123-4567 모양으로 하이픈을 넣는다. */
  setStorePhone: (value: string) => void;
  storePhoto: File | null;
  setStorePhoto: (file: File | null) => void;
  /** 세 칸이 모두 맞을 때만 true. 대표 사진은 선택이라 검사하지 않는다. */
  canProceed: boolean;
  goNext: () => void;
};

/** 가게 등록 1: 상호명·주소·가게 전화번호·대표 사진. */
export const useSignupStore = (): UseSignupStoreResult => {
  const navigate = useNavigate();
  const { store, setStore } = useSignupDraft();

  const [storeName, setStoreName] = useState(store?.storeName ?? "");
  const [storeAddress, setStoreAddress] = useState(store?.storeAddress ?? "");
  const [storePhone, setStorePhone] = useState(store?.storePhone ?? "");
  // 선택이다. 고른 파일은 들고 있다가 가입 신청을 보낼 때 올린다(hooks/signupMenus.ts).
  const [storePhoto, setStorePhoto] = useState<File | null>(store?.storePhoto ?? null);

  const canProceed = [
    validateStoreName(storeName),
    validateStoreAddress(storeAddress),
    validateStorePhone(storePhone),
  ].every((message) => message === null);

  const goNext = () => {
    if (!canProceed) return;
    setStore({ storeName, storeAddress, storePhone, storePhoto });
    navigate(NEXT_PATH);
  };

  return {
    storeName,
    setStoreName,
    storeAddress,
    setStoreAddress,
    storePhone,
    setStorePhone: (value) => setStorePhone(formatStorePhone(value)),
    storePhoto,
    setStorePhoto,
    canProceed,
    goNext,
  };
};
