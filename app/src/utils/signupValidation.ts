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
