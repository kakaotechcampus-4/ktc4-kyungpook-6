import type { OwnerSignupRequest } from "../services/auth";
import type { BizCertReading } from "../services/file";

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

type SignupExtras = Pick<OwnerSignupRequest, "openDate" | "corpName" | "businessType" | "businessItem">;

/** 가입 화면에 칸이 없는 선택 값. 읽은 것만 싣고, 못 읽은 칸은 빼서 보낸다. */
export const toSignupExtras = (reading: BizCertReading | null): SignupExtras => {
  if (!reading) return {};
  const extras: SignupExtras = {};
  if (reading.openDate) extras.openDate = reading.openDate;
  if (reading.corpName?.trim()) extras.corpName = reading.corpName.trim();
  if (reading.businessType?.trim()) extras.businessType = reading.businessType.trim();
  if (reading.businessItem?.trim()) extras.businessItem = reading.businessItem.trim();
  return extras;
};
