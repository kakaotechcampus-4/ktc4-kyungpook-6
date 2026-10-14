/** 숫자만 남긴다. */
export const toDigits = (value: string) => value.replace(/\D/g, "");

/**
 * 휴대폰 번호를 입력하는 대로 하이픈을 넣는다. 최대 11자리.
 * 010-1234-5678(11자리), 011-123-4567(10자리)
 */
export const formatPhone = (value: string) => {
  const digits = toDigits(value).slice(0, 11);
  if (digits.length <= 3) return digits;
  if (digits.length <= 7) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  if (digits.length === 10) return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
};

/**
 * 백엔드 LocalDateTime 문자열("2026-09-29T12:31:05")에서 시:분("12:31")만 꺼낸다.
 * 시간대 없이 오는 값이라 Date 로 바꾸지 않고 글자 그대로 자른다. 모양이 다르면 빈 문자열.
 */
export const formatHourMinute = (localDateTime: string) => {
  const match = /T(\d{2}):(\d{2})/.exec(localDateTime);
  return match ? `${match[1]}:${match[2]}` : "";
};

/** 사업자등록번호를 입력하는 대로 XXX-XX-XXXXX 로 나눈다. 최대 10자리. */
export const formatBizNo = (value: string) => {
  const digits = toDigits(value).slice(0, 10);
  if (digits.length <= 3) return digits;
  if (digits.length <= 5) return `${digits.slice(0, 3)}-${digits.slice(3)}`;
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
};
