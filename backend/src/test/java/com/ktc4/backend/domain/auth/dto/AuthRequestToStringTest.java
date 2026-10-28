package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 요청 객체가 로그에 찍혀도 개인정보가 새지 않는지 확인한다. 값은 모두 가짜다.
@DisplayName("로그인·가입 요청의 toString")
class AuthRequestToStringTest {

    @Test
    @DisplayName("가입 요청은 비밀번호·대표자 이름·휴대폰 번호·파일 ID 를 가리고 이메일은 일부만 보인다")
    void signupRequestMasksPersonalData() {
        String text = new OwnerSignupRequest("owner@example.com", "secret-password", "1234567890",
                "예시분식", "홍길동", "010-0000-0000", null, null, null, null, "대구광역시 북구 대학로 80", "053-123-4567",
                List.of(new BusinessHourRequest(BusinessDay.MON, "11:00", "21:00")),
                List.of(new SignupMenuRequest("김치찌개", "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")),
                "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f", "7b1d0e4f-2c3a-4d5b-8e6f-9a0b1c2d3e4f").toString();

        assertThat(text).contains("o***@example.com")
                .doesNotContain("owner@example.com")
                .doesNotContain("secret-password")
                .doesNotContain("홍길동")
                .doesNotContain("010-0000-0000")
                .doesNotContain("053-123-4567")
                .doesNotContain("김치찌개")
                // 파일 ID 는 알면 그 파일을 신청에 붙일 수 있는 값이다
                .doesNotContain("9c8b7a6d").doesNotContain("3f2a9c1e").doesNotContain("7b1d0e4f");
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
