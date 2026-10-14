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
  /** 세 칸이 모두 맞을 때만 true. 대표 사진은 서버가 받지 않아 검사하지 않는다(아래 TODO). */
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
  /*
    TODO(백엔드 가게 사진 업로드): 가입 요청(OwnerSignupRequest)에 사진 칸이 없어 고른 파일을 들고만 있다.
    업로드 API 가 생기면 보내고, 필수라면 canProceed 조건에 넣는다.
  */
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
