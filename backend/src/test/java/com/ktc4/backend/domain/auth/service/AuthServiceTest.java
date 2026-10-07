package com.ktc4.backend.domain.auth.service;

import com.ktc4.backend.domain.auth.dto.LoginRequest;
import com.ktc4.backend.domain.auth.dto.LoginResponse;
import com.ktc4.backend.domain.auth.dto.MemberResponse;
import com.ktc4.backend.domain.auth.dto.OwnerSignupRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    // ── 점주 가입 신청 ─────────────────────────────────────────────

    private static OwnerSignupRequest signup(String email, String password, String bizNo) {
        return signup(email, password, bizNo, "010-0000-0000");
    }

    private static OwnerSignupRequest signup(String email, String password, String bizNo, String phone) {
        return new OwnerSignupRequest(email, password, bizNo, " 예시분식 ", " 홍길동 ", phone);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"010-0000-0000", "010 0000 0000", "01000000000", "+82 10-0000-0000", "０１０-0000-0000"})
    @DisplayName("휴대폰 번호는 표기가 달라도 숫자만 남겨 같은 값으로 저장한다")
    void normalizesPhone(String phone) {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890", phone));

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerInfo().getPhone()).isEqualTo("01000000000");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"053-000-0000", "010-0000", "012-0000-0000", "전화없음", "010-0000-0000-0"})
    @DisplayName("휴대폰 번호 형식이 아니면 INVALID_PHONE 이고 저장하지 않는다 — 매장 전화, 자릿수 부족, 없는 앞자리")
    void rejectsInvalidPhone(String phone) {
        assertThat(errorCodeOf(() -> authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890", phone))))
                .isEqualTo(ErrorCode.INVALID_PHONE);
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("가입 신청은 승인 대기 점주로 저장하고, 비밀번호는 암호화·사업자번호는 10자리로 맞춘다")
    void signsUpPendingOwner() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MemberResponse response = authService.signupOwner(signup(" Owner@Example.com ", PASSWORD, "123-45-67890"));

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        Member member = saved.getValue();
        assertThat(member.getEmail()).isEqualTo(EMAIL);
        assertThat(member.getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(member.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(ENCODER.matches(PASSWORD, member.getPasswordHash())).isTrue();
        assertThat(member.getOwnerInfo().getBizNo()).isEqualTo("1234567890");
        assertThat(member.getOwnerInfo().getStoreName()).isEqualTo("예시분식");
        assertThat(member.getOwnerInfo().getRepresentativeName()).isEqualTo("홍길동");
        assertThat(member.getOwnerInfo().getReviewedAt()).isNull();
        assertThat(response.status()).isEqualTo(MemberStatus.PENDING);
    }

    @Test
    @DisplayName("가입 신청한 점주는 승인 전까지 로그인할 수 없다")
    void appliedOwnerCannotLoginUntilApproved() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890"));
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(saved.capture());
        givenMember(saved.getValue());

        assertThat(errorCodeOf(() -> authService.login(new LoginRequest(EMAIL, PASSWORD))))
                .isEqualTo(ErrorCode.OWNER_PENDING_APPROVAL);
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL")
    void rejectsDuplicateEmail() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThat(errorCodeOf(() -> authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890"))))
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
        verify(memberRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("확인 뒤 동시에 같은 이메일이 저장돼 이메일 제약에 걸려도 DUPLICATE_EMAIL")
    void mapsUniqueViolationToDuplicateEmail() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(memberRepository.saveAndFlush(any())).thenThrow(violationOf(Member.EMAIL_UNIQUE_CONSTRAINT));

        assertThat(errorCodeOf(() -> authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890"))))
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("이메일이 아닌 다른 제약 위반은 그대로 던진다 — 원인을 잃지 않게")
    void rethrowsOtherConstraintViolations() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
        DataIntegrityViolationException other = violationOf("some_other_constraint");
        when(memberRepository.saveAndFlush(any())).thenThrow(other);

        assertThat(catchThrowable(() -> authService.signupOwner(signup(EMAIL, PASSWORD, "1234567890"))))
                .isSameAs(other);
    }

    // Spring 이 Hibernate 의 제약 위반을 감싸 던지는 모양을 그대로 만든다.
    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("constraint violation",
                new org.hibernate.exception.ConstraintViolationException(
                        "duplicate", new java.sql.SQLException("duplicate", "23505"), constraintName));
    }

    @Test
    @DisplayName("사업자등록번호가 숫자 10자리가 아니면 INVALID_BIZ_NO")
    void rejectsInvalidBizNo() {
        assertThat(errorCodeOf(() -> authService.signupOwner(signup(EMAIL, PASSWORD, "123-45-678"))))
                .isEqualTo(ErrorCode.INVALID_BIZ_NO);
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("글자 수는 72 이하여도 72바이트를 넘는 비밀번호(한글 등)는 PASSWORD_TOO_LONG")
    void rejectsPasswordOver72Bytes() {
        String koreanPassword = "가".repeat(25);   // 25자, 75바이트

        assertThat(errorCodeOf(() -> authService.signupOwner(signup(EMAIL, koreanPassword, "1234567890"))))
                .isEqualTo(ErrorCode.PASSWORD_TOO_LONG);
        verifyNoInteractions(memberRepository);
    }
}
