package com.ktc4.backend.domain.auth.service;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 회원 조회만 가짜로 두고, 비밀번호 암호화와 토큰 발급은 실제 구현으로 확인한다. 계정 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    // 강도 4 는 테스트를 빠르게 하려고 낮춘 값이다. 운영은 SecurityConfig 의 기본 강도(10)를 쓴다.
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");
    private static final String EMAIL = "owner@example.com";
    private static final String PASSWORD = "correct-password";

    @Mock
    private MemberRepository memberRepository;

    private JwtProvider jwtProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider("test-only-jwt-secret-at-least-32-bytes-long",
                Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC));
        authService = new AuthService(memberRepository, ENCODER, jwtProvider);
    }

    // 점주 가입 기능이 아직 없어 관리자로 만든 뒤 역할·상태를 바꿔 쓴다.
    private static Member member(MemberRole role, MemberStatus status) {
        Member member = Member.admin(EMAIL, ENCODER.encode(PASSWORD));
        ReflectionTestUtils.setField(member, "memberId", 1L);
        ReflectionTestUtils.setField(member, "role", role);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

    private void givenMember(Member member) {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        CustomException e = (CustomException) catchThrowable(call::run);
        return e.getErrorCode();
    }

    @Test
    @DisplayName("비밀번호가 맞으면 회원 ID 와 역할을 담은 토큰을 발급한다")
    void issuesTokenOnSuccess() {
        givenMember(member(MemberRole.OWNER, MemberStatus.APPROVED));

        LoginResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.role()).isEqualTo(MemberRole.OWNER);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(jwtProvider.parse(response.accessToken())).contains(new AuthMember(1L, MemberRole.OWNER));
    }

    @Test
    @DisplayName("이메일의 앞뒤 공백과 대소문자는 무시한다")
    void normalizesEmail() {
        givenMember(member(MemberRole.ADMIN, MemberStatus.APPROVED));

        authService.login(new LoginRequest("  Owner@Example.COM ", PASSWORD));

        verify(memberRepository).findByEmail(EMAIL);
    }

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호는 같은 에러를 준다 — 가입 여부를 알 수 없게")
    void sameErrorForUnknownEmailAndWrongPassword() {
        when(memberRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        givenMember(member(MemberRole.OWNER, MemberStatus.APPROVED));

        assertThat(errorCodeOf(() -> authService.login(new LoginRequest("nobody@example.com", PASSWORD))))
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, "wrong-password"))))
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("72바이트를 넘는 비밀번호는 회원을 찾아보지도 않고 틀린 비밀번호로 처리한다")
    void rejectsTooLongPassword() {
        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, "a".repeat(73)))))
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("승인 대기 중인 점주는 비밀번호가 맞아도 로그인할 수 없다")
    void rejectsPendingOwner() {
        givenMember(member(MemberRole.OWNER, MemberStatus.PENDING));

        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, PASSWORD))))
                .isEqualTo(ErrorCode.OWNER_PENDING_APPROVAL);
    }

    @Test
    @DisplayName("가입이 거절된 점주는 로그인할 수 없다")
    void rejectsRejectedOwner() {
        givenMember(member(MemberRole.OWNER, MemberStatus.REJECTED));

        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, PASSWORD))))
                .isEqualTo(ErrorCode.OWNER_REJECTED);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 승인 대기 여부를 알려주지 않는다")
    void hidesStatusWhenPasswordWrong() {
        givenMember(member(MemberRole.OWNER, MemberStatus.PENDING));

        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, "wrong-password"))))
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("내 정보 조회는 토큰의 회원 ID 로 찾는다")
    void getsMe() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member(MemberRole.OWNER, MemberStatus.APPROVED)));

        MemberResponse me = authService.getMe(1L);

        assertThat(me.memberId()).isEqualTo(1L);
        assertThat(me.email()).isEqualTo(EMAIL);
        assertThat(me.role()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("토큰 발급 뒤 계정이 지워졌으면 MEMBER_NOT_FOUND")
    void getMeFailsWhenDeleted() {
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getMe(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }
}
