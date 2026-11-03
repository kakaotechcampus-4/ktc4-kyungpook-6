import type { SignupBusinessPair, SignupInfo, SignupStore } from "../hooks/signupDraft";
import type { BizCertReading } from "../services/file";
import { formatBizNo, isoToOpenDate } from "./format";

/**
 * 칸이 비었을 때만 읽은 값으로 채운다. 점주가 이미 적은 값은 덮어쓰지 않는다(백엔드 API 설명과 같다).
 * 못 읽었거나(null) 읽은 값이 공백뿐이면 그대로 둔다.
 */
export const fillBlank = (current: string, read: string | null) => {
  if (current.trim() !== "" || !read?.trim()) return current;
  return read.trim();
};

/** 읽은 칸이 하나라도 있는가. 서버는 아무것도 못 읽어도 200 에 모든 칸을 null 로 준다. */
export const hasAnyReading = (reading: BizCertReading) =>
  [
    reading.bizNo,
    reading.storeName,
    reading.representativeName,
    reading.storeAddress,
    reading.openDate,
    reading.corpName,
    reading.businessType,
    reading.businessItem,
  ].some((value) => Boolean(value?.trim()));

const hasValue = (value: string | null) => Boolean(value?.trim());

/** 사업자등록증 화면들이 "자동으로 채워지는 정보"로 보여 주는 항목. 순서가 화면 순서다. */
const AUTOFILL_ITEMS: { label: string; isRead: (reading: BizCertReading) => boolean }[] = [
  { label: "대표자명", isRead: (reading) => hasValue(reading.representativeName) },
  { label: "사업자등록번호", isRead: (reading) => hasValue(reading.bizNo) },
  { label: "개업일", isRead: (reading) => hasValue(reading.openDate) },
  {
    label: "업태 · 종목",
    isRead: (reading) => hasValue(reading.businessType) || hasValue(reading.businessItem),
  },
  { label: "사업장 주소", isRead: (reading) => hasValue(reading.storeAddress) },
];

export type AutofillItem = { label: string; read: boolean };

/** 항목마다 읽었는지. reading 이 없으면(올리기 전·못 읽음) 모두 false. */
export const toAutofillItems = (reading: BizCertReading | null): AutofillItem[] =>
  AUTOFILL_ITEMS.map(({ label, isRead }) => ({ label, read: reading !== null && isRead(reading) }));

/** 쉼표로 이은 업태·종목을 화면의 한 줄씩(업태 · 종목 짝)으로 나눈다. 비면 빈 줄 하나. */
export const splitBusinessPairs = (
  businessType: string | null,
  businessItem: string | null,
): { type: string; item: string }[] => {
  const split = (value: string | null) =>
    (value ?? "")
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part !== "");
  const types = split(businessType);
  const items = split(businessItem);
  const count = Math.max(types.length, items.length, 1);
  return Array.from({ length: count }, (_, index) => ({ type: types[index] ?? "", item: items[index] ?? "" }));
};

/** 화면의 업태·종목 줄을 백엔드 모양(쉼표로 이은 한 칸)으로. 하나도 없으면 undefined 라 싣지 않는다. */
export const joinBusinessValues = (values: string[]) => {
  const joined = values
    .map((value) => value.trim())
    .filter((value) => value !== "")
    .join(", ");
  return joined === "" ? undefined : joined;
};

/** 읽은 업태·종목을 화면 줄로. newId 로 줄마다 키를 붙인다. */
export const toBusinessPairs = (reading: BizCertReading | null, newId: () => string): SignupBusinessPair[] =>
  splitBusinessPairs(reading?.businessType ?? null, reading?.businessItem ?? null).map((pair) => ({
    id: newId(),
    ...pair,
  }));

/**
 * 기본 정보를 이미 적고 돌아와 등록증을 다시 올렸을 때. 빈 칸만 새로 읽은 값으로 채운다.
 * 업태·종목은 줄이 모두 비었을 때만 읽은 줄로 바꾼다.
 */
export const fillInfoBlanks = (info: SignupInfo, reading: BizCertReading, newId: () => string): SignupInfo => {
  const pairsBlank = info.businessPairs.every((pair) => pair.type.trim() === "" && pair.item.trim() === "");
  return {
    ...info,
    representativeName: fillBlank(info.representativeName, reading.representativeName),
    corpName: fillBlank(info.corpName, reading.corpName),
    bizNo: formatBizNo(fillBlank(info.bizNo, reading.bizNo)),
    openDate: fillBlank(info.openDate, reading.openDate ? isoToOpenDate(reading.openDate) : null),
    businessPairs: pairsBlank ? toBusinessPairs(reading, newId) : info.businessPairs,
  };
};

/** 가게 정보를 이미 적고 돌아와 등록증을 다시 올렸을 때. 빈 상호명·주소만 채운다. */
export const fillStoreBlanks = (store: SignupStore, reading: BizCertReading): SignupStore => ({
  ...store,
  storeName: fillBlank(store.storeName, reading.storeName),
  storeAddress: fillBlank(store.storeAddress, reading.storeAddress),
});
