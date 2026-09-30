package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.global.util.LogMasking;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 점주 가입 신청.
 *
 * <p>사업자 정보는 관리자가 승인 여부를 판단하는 자료다. 사업자등록번호는 하이픈이 있어도 되고, 서버가
 * 숫자 10자리로 맞춰 확인한다 — 형식 확인을 여기서 하지 않는 이유는 "123-45-67890" 같은 표기를 그대로 받기 위해서다.
 *
 * <p>상호명·대표자 이름은 두 겹으로 막는다.
 * <ol>
 *   <li><b>글자나 숫자가 하나 이상</b> 있어야 한다 — 전각 공백(U+3000)처럼 {@code @NotBlank} 를 통과하지만
 *       서비스의 {@code strip()} 에서 지워져 빈 값이 되는 글자, 기호·빈칸만으로 된 이름을 막는다.
 *       막는 문자 목록에 없는 새로운 "빈 글자"가 와도 이 규칙이 한 번 더 막는다.</li>
 *   <li><b>보이지 않거나 화면을 흐트러뜨리는 글자</b>를 막는다 — 줄바꿈·탭(Cc), 폭 없는 공백·글자 방향 뒤집기(Cf),
 *       줄·문단 구분자(Zl, Zp), 글자로 분류되지만 보이지 않는 한글 채움 문자(U+115F, U+1160, U+3164, U+FFA0),
 *       점자 빈칸(U+2800), 보이지 않는 결합 글자 연결자(U+034F), 사용자 정의 영역(Co)·아직 배정되지 않은 코드(Cn).
 *       관리자 화면·로그·CSV 에서 이름이 비어 보이거나 뒤집혀 보이지 않게 한다.</li>
 *   <li><b>결합 부호를 3개 이상 겹쳐 쓰지 못하게</b> 한다 — 글자 하나에 부호를 수십 개 쌓아 화면을 망가뜨리는
 *       문자열을 막는다. ❤️ 처럼 이모지에 붙는 부호 하나는 통과한다.</li>
 * </ol>
 * Cf 에는 이모지를 이어 붙이는 글자(ZWJ, U+200D)도 있어 가족 이모지 같은 결합 이모지는 거절된다 —
 * 상호명·대표자 이름에서는 받아들일 만한 제약으로 판단했다.
 *
 * <p>비밀번호 최대 길이(72)는 글자 수 기준이다. BCrypt 는 바이트 기준으로 72까지만 비교하므로, 한글처럼
 * 여러 바이트인 글자로 72바이트를 넘기면 {@code AuthService} 가 한 번 더 거절한다.
 */
public record OwnerSignupRequest(
        @Schema(description = "로그인 이메일", example = "owner@example.com")
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다")
        String email,

        @Schema(description = "비밀번호 (8~72자)", example = "password1234")
        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다")
        String password,

        @Schema(description = "사업자등록번호 (하이픈 있어도 됨)", example = "123-45-67890")
        @NotBlank(message = "사업자등록번호를 입력해 주세요")
        @Size(max = 20, message = "사업자등록번호가 너무 깁니다")
        String bizNo,

        @Schema(description = "상호명", example = "예시분식")
        @NotBlank(message = "상호명을 입력해 주세요")
        @Size(max = 200, message = "상호명은 200자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "상호명에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "상호명에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "상호명에 겹쳐 쓴 부호가 너무 많습니다")
        String storeName,

        @Schema(description = "대표자 이름", example = "홍길동")
        @NotBlank(message = "대표자 이름을 입력해 주세요")
        @Size(max = 50, message = "대표자 이름은 50자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "대표자 이름에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "대표자 이름에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "대표자 이름에 겹쳐 쓴 부호가 너무 많습니다")
        String representativeName
) {
    // 글자(L)나 숫자(N)가 최소 하나. (?s): 줄바꿈이 섞여도 . 이 매칭되게 한다(줄바꿈 자체는 아래 규칙이 막는다).
    static final String HAS_LETTER_OR_DIGIT = "(?s).*[\\p{L}\\p{N}].*";
    // 제어(Cc)·서식(Cf) 문자, 사용자 정의 영역(Co), 배정 안 된 코드(Cn), 줄·문단 구분자(Zl, Zp),
    // 한글 채움 문자 4개, 점자 빈칸, 결합 글자 연결자(U+034F)를 막는다.
    static final String NO_INVISIBLE_CHARACTERS =
            "[^\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Zl}\\p{Zp}\\u115F\\u1160\\u3164\\uFFA0\\u2800\\u034F]*";
    // 결합 부호(M)가 3개 이상 연달아 오면 안 된다. (?!...) 는 "뒤에 이런 게 없어야 한다"는 뜻이다.
    static final String NO_STACKED_MARKS = "(?s)(?!.*\\p{M}{3}).*";

    // 로그에 요청 객체가 찍혀도 비밀번호·대표자 이름이 새지 않고 이메일은 일부만 보이게 한다.
    // record 기본 toString 은 모든 필드를 그대로 출력한다.
    @Override
    public String toString() {
        return "OwnerSignupRequest[email=" + LogMasking.maskEmail(email) + ", password=****, bizNo=" + bizNo
                + ", storeName=" + storeName + ", representativeName=****]";
    }
}
