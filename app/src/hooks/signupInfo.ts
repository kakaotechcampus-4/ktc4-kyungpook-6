import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { newDraftItemId, useSignupDraft } from "./signupDraft";
import type { SignupBusinessPair } from "./signupDraft";
import { formatBizNo, formatOpenDate, isoToOpenDate } from "../utils/format";
import { fillBlank, joinBusinessValues, toBusinessPairs } from "../utils/bizCertReading";
import {
  BUSINESS_PAIR_MAX_COUNT,
  isValidOptionalBizText,
  validateBizNo,
  validateCorpName,
  validateOpenDate,
  validateRepresentativeName,
} from "../utils/signupValidation";

/** 기본 정보 다음 화면(가게 등록 1). */
const NEXT_PATH = "/owner/signup/store";

type PairField = "type" | "item";

const emptyPair = (): SignupBusinessPair => ({ id: newDraftItemId(), type: "", item: "" });

export type UseSignupInfoResult = {
  /** 사업자등록증에서 하나라도 읽었으면 true. "가져왔어요" 안내를 띄운다. */
  autofilled: boolean;
  representativeName: string;
  setRepresentativeName: (value: string) => void;
  corpName: string;
  setCorpName: (value: string) => void;
  bizNo: string;
  /** 입력하는 대로 XXX-XX-XXXXX 모양으로 하이픈을 넣는다. */
  setBizNo: (value: string) => void;
  openDate: string;
  /** 입력하는 대로 YYYY.MM.DD 모양으로 점을 넣는다. */
  setOpenDate: (value: string) => void;
  businessPairs: SignupBusinessPair[];
  changePair: (id: string, field: PairField, value: string) => void;
  /** 5줄이면 false 라 + 버튼을 끈다. */
  canAddPair: boolean;
  addPair: () => void;
  removePair: (id: string) => void;
  /** 필수 칸(대표자명·사업자등록번호)이 맞고 선택 칸도 적었다면 맞을 때만 true. */
  canProceed: boolean;
  /** "다음" 버튼. 입력값을 보관하고 가게 등록으로 넘어간다. 가입 신청은 마지막 단계(메뉴)에서 보낸다. */
  goNext: () => void;
};

/** 기본 정보: 대표자명·법인명·사업자등록번호·개업일·업태 종목. 처음에는 사업자등록증에서 읽은 값으로 채운다. */
export const useSignupInfo = (): UseSignupInfoResult => {
  const navigate = useNavigate();
  const { bizCert, info, setInfo } = useSignupDraft();
  const reading = bizCert?.reading ?? null;

  // 뒤 단계에서 돌아왔으면 적어 둔 값을, 처음이면 읽은 값을 채운다.
  const [representativeName, setRepresentativeName] = useState(
    () => info?.representativeName ?? fillBlank("", reading?.representativeName ?? null),
  );
  const [corpName, setCorpName] = useState(() => info?.corpName ?? fillBlank("", reading?.corpName ?? null));
  const [bizNo, setBizNo] = useState(() => info?.bizNo ?? formatBizNo(fillBlank("", reading?.bizNo ?? null)));
  const [openDate, setOpenDate] = useState(
    () => info?.openDate ?? (reading?.openDate ? isoToOpenDate(reading.openDate) : ""),
  );
  const [businessPairs, setBusinessPairs] = useState<SignupBusinessPair[]>(
    () => info?.businessPairs ?? toBusinessPairs(reading, newDraftItemId),
  );

  // 하나도 못 읽었으면 reading 이 null 이다(hooks/signupBizCert.ts).
  const autofilled = reading !== null;

  const changePair = (id: string, field: PairField, value: string) => {
    setBusinessPairs((prev) => prev.map((pair) => (pair.id === id ? { ...pair, [field]: value } : pair)));
  };

  const canAddPair = businessPairs.length < BUSINESS_PAIR_MAX_COUNT;

  // 백엔드는 줄마다가 아니라 쉼표로 이은 값 전체를 200자 이하·글자 규칙으로 본다.
  const isValidJoined = (values: string[]) => isValidOptionalBizText(joinBusinessValues(values) ?? "");

  const canProceed =
    [
      validateRepresentativeName(representativeName),
      validateCorpName(corpName),
      validateBizNo(bizNo),
      validateOpenDate(openDate),
    ].every((message) => message === null) &&
    isValidJoined(businessPairs.map((pair) => pair.type)) &&
    isValidJoined(businessPairs.map((pair) => pair.item));

  const goNext = () => {
    if (!canProceed) return;
    setInfo({ representativeName, corpName, bizNo, openDate, businessPairs });
    navigate(NEXT_PATH);
  };

  return {
    autofilled,
    representativeName,
    setRepresentativeName,
    corpName,
    setCorpName,
    bizNo,
    setBizNo: (value) => setBizNo(formatBizNo(value)),
    openDate,
    setOpenDate: (value) => setOpenDate(formatOpenDate(value)),
    businessPairs,
    changePair,
    canAddPair,
    addPair: () => {
      if (canAddPair) setBusinessPairs((prev) => [...prev, emptyPair()]);
    },
    removePair: (id) => setBusinessPairs((prev) => prev.filter((pair) => pair.id !== id)),
    canProceed,
    goNext,
  };
};
