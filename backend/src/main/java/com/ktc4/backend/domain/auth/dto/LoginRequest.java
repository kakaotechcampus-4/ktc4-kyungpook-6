package com.ktc4.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청.
 *
 * <p>비밀번호에는 길이 검증을 걸지 않는다. 검증에 실패하면 공통 에러 핸들러가 예외 메시지를 로그로 남기는데,
 * 그 메시지에 거절된 입력값이 그대로 들어가 <b>비밀번호가 서버 로그에 찍힌다</b>. 너무 긴 비밀번호는
 * {@code AuthService} 가 "틀린 비밀번호"로 처리한다({@code AuthControllerTest} 가 로그에 안 남는지 확인한다).
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
    // 로그에 요청 객체가 찍혀도 비밀번호가 새지 않게 한다. record 기본 toString 은 모든 필드를 출력한다.
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
