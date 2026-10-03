package com.ktc4.backend.domain.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 요청 객체가 로그에 찍혀도 개인정보가 새지 않는지 확인한다. 값은 모두 가짜다.
@DisplayName("로그인·가입 요청의 toString")
class AuthRequestToStringTest {

    @Test
    @DisplayName("가입 요청은 비밀번호·대표자 이름을 가리고 이메일은 일부만 보인다")
    void signupRequestMasksPersonalData() {
        String text = new OwnerSignupRequest("owner@example.com", "secret-password", "1234567890",
                "예시분식", "홍길동").toString();

        assertThat(text).contains("o***@example.com")
                .doesNotContain("owner@example.com")
                .doesNotContain("secret-password")
                .doesNotContain("홍길동");
    }

    @Test
    @DisplayName("로그인 요청은 비밀번호를 가리고 이메일은 일부만 보인다")
    void loginRequestMasksPersonalData() {
        String text = new LoginRequest("owner@example.com", "secret-password").toString();

        assertThat(text).contains("o***@example.com")
                .doesNotContain("owner@example.com")
                .doesNotContain("secret-password");
    }
}
