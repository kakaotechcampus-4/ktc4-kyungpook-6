package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.global.util.LogMasking;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청.
 *
 * <p>비밀번호에는 길이 검증을 걸지 않는다. 로그인에서는 너무 긴 비밀번호도 "틀린 비밀번호"와 같아서,
 * 400 으로 형식 오류를 알려줄 이유가 없다 — {@code AuthService} 가 401 로 처리한다.
 * (검증 실패 로그에 입력값이 남던 문제는 {@code GlobalExceptionHandler} 에서 값 없이 필드 이름과 이유만 남기도록 고쳐졌다.
 * {@code AuthControllerTest} 는 긴 비밀번호가 로그에 남지 않는지 계속 확인한다.)
 */
public record LoginRequest(
        @Schema(description = "이메일", example = "owner@example.com")
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다")
        String email,

        @Schema(description = "비밀번호", example = "password1234")
        @NotBlank(message = "비밀번호를 입력해 주세요")
        String password
) {
    // 로그에 요청 객체가 찍혀도 비밀번호가 새지 않고 이메일은 일부만 보이게 한다. record 기본 toString 은 모든 필드를 출력한다.
    @Override
    public String toString() {
        return "LoginRequest[email=" + LogMasking.maskEmail(email) + ", password=****]";
    }
}
