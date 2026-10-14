import { toDigits } from "./format";

/*
  회원가입 입력 검사. 틀렸으면 화면 문구를, 맞으면 null 을 돌려준다.

  백엔드 OwnerSignupRequest·AuthService 의 규칙을 그대로 옮겼다.
  앱이 통과시킨 값은 백엔드도 반드시 통과해야 한다. 백엔드 규칙이 바뀌면 여기도 같이 바꾼다.
*/

/** 백엔드 @Size(max = 254) */
export const EMAIL_MAX_LENGTH = 254;
/** 백엔드 @Size(min = 8, max = 72) */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 72;
/** 백엔드 AuthService.MAX_PASSWORD_BYTES — BCrypt 가 앞 72바이트만 비교해서 둔 제한(PASSWORD_TOO_LONG) */
const PASSWORD_MAX_BYTES = 72;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
/** 백엔드 PhoneNormalizer.MOBILE — 010·011·016·017·018·019 로 시작하는 10~11자리 */
const MOBILE_PATTERN = /^01[016789]\d{7,8}$/;
/** 백엔드 HAS_LETTER_OR_DIGIT — 글자나 숫자가 하나 이상 */
const HAS_LETTER_OR_DIGIT = /[\p{L}\p{N}]/u;
/** 백엔드 NO_INVISIBLE_CHARACTERS — 줄바꿈·제어·서식 문자, 한글 채움 문자 등 보이지 않는 글자 */
// U+034F(결합 글자 연결자)는 결합 문자라 [...] 안에 넣으면 린트가 막아서 따로 뺐다. 뜻은 백엔드와 같다.
const INVISIBLE_CHARACTER = /[\p{Cc}\p{Cf}\p{Co}\p{Cn}\p{Zl}\p{Zp}\u115F\u1160\u3164\uFFA0\u2800]|\u034F/u;
/** 백엔드 NO_STACKED_MARKS — 결합 부호가 3개 이상 연달아 */
const STACKED_MARKS = /\p{M}{3}/u;

/** 대표자 이름·상호명 공통 규칙. 백엔드는 앞뒤 공백을 지우기 전 값으로 검사한다. */
const isValidName = (raw: string, maxLength: number) => {
  const value = raw.trim();
  return (
    value !== "" &&
    value.length <= maxLength &&
    HAS_LETTER_OR_DIGIT.test(raw) &&
    !INVISIBLE_CHARACTER.test(raw) &&
    !STACKED_MARKS.test(raw)
  );
};

/** 이메일 중복 문구. 형식 검사와 달리 서버에 물어봐야 알 수 있다(hooks/signupAccount.ts). */
export const DUPLICATE_EMAIL_MESSAGE = "이미 사용 중인 아이디예요";

export const validateEmail = (email: string) => {
  const value = email.trim();
  if (!EMAIL_PATTERN.test(value) || value.length > EMAIL_MAX_LENGTH) {
    return "이메일 형식으로 입력해주세요";
  }
  return null;
};

/*
  백엔드와 같은 순서로 본다: @NotBlank → @Size(8~72자) → 72바이트.
  자 수는 자바 String.length 와 같은 JS length(UTF-16)로 센다.
  한글·이모지는 한 글자가 3~4바이트라 72자가 안 돼도 72바이트를 넘을 수 있다.
*/
export const validatePassword = (password: string) => {
  if (password.trim() === "") return "비밀번호를 입력해주세요";
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    return "비밀번호는 8~72자로 입력해주세요";
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return "비밀번호가 너무 길어요";
  }
  return null;
};

export const validatePasswordConfirm = (password: string, confirm: string) =>
  confirm !== "" && confirm === password ? null : "비밀번호가 일치하지 않아요";

/** 백엔드 대표자 이름: 필수, 50자 이하, 글자나 숫자 포함, 보이지 않는 글자·겹친 부호 금지 */
export const validateRepresentativeName = (name: string) =>
  isValidName(name, 50) ? null : "대표자명을 입력해주세요";

/** 백엔드 상호명: 필수, 200자 이하, 글자나 숫자 포함, 보이지 않는 글자·겹친 부호 금지 */
export const validateStoreName = (storeName: string) =>
  isValidName(storeName, 200) ? null : "상호명을 입력해주세요";

export const validatePhone = (phone: string) =>
  MOBILE_PATTERN.test(toDigits(phone)) ? null : "휴대폰 번호를 확인해주세요";

/** 백엔드 BizNoNormalizer — 숫자 10자리 */
export const validateBizNo = (bizNo: string) =>
  toDigits(bizNo).length === 10 ? null : "사업자등록번호 10자리를 입력해주세요";

/** 백엔드 storeAddress @Size(max = 500) */
export const STORE_ADDRESS_MAX_LENGTH = 500;
/** 백엔드 storePhone @Size(max = 20) */
export const STORE_PHONE_MAX_LENGTH = 20;
/** 백엔드 MenuRequest.MAX_NAME_LENGTH·MAX_COUNT */
export const MENU_NAME_MAX_LENGTH = 50;
export const MENU_MAX_COUNT = 50;
/** 백엔드 BusinessHourRequest.MAX_LINES */
export const BUSINESS_HOUR_MAX_LINES = 21;

/** 백엔드 가게 주소: 필수(@NotBlank), 500자 이하. 글자 규칙은 따로 없다. */
export const validateStoreAddress = (address: string) =>
  address.trim() !== "" && address.length <= STORE_ADDRESS_MAX_LENGTH ? null : "가게 주소를 입력해주세요";

/*
  백엔드는 가게 전화번호를 @NotBlank·20자 이하로만 본다(유선 번호도 받으려고 형식은 보지 않는다).
  앱은 숫자로만 입력받으므로 국내 전화번호 길이(대표번호 8자리 ~ 휴대폰 11자리)만 확인한다.
*/
export const validateStorePhone = (storePhone: string) => {
  const length = toDigits(storePhone).length;
  return length >= 8 && length <= 11 && storePhone.length <= STORE_PHONE_MAX_LENGTH
    ? null
    : "가게 전화번호를 확인해주세요";
};

/** 백엔드 메뉴 이름: 상호명과 같은 글자 규칙, 50자 이하 */
export const validateMenuName = (name: string) =>
  isValidName(name, MENU_NAME_MAX_LENGTH) ? null : "메뉴 이름을 확인해주세요";

/** 백엔드 BusinessHourRequest 의 OPEN_TIME — 00:00 ~ 23:59 */
const OPEN_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;
/** 백엔드 BusinessHourRequest 의 CLOSE_TIME — 00:01 ~ 23:59 와 24:00 */
const CLOSE_TIME_PATTERN = /^(?!00:00)(([01]\d|2[0-3]):[0-5]\d|24:00)$/;

/** 영업시간 한 줄: 요일을 골랐고, 시간 형식이 맞고, 여는 시간과 닫는 시간이 다른지(백엔드 isOpenDifferentFromClose). */
export const validateBusinessHour = ({ day, open, close }: { day: string; open: string; close: string }) => {
  if (day === "") return "요일을 골라주세요";
  if (!OPEN_TIME_PATTERN.test(open) || !CLOSE_TIME_PATTERN.test(close)) return "시간을 골라주세요";
  if (open === close) return "여는 시간과 닫는 시간이 같아요";
  return null;
};
