package com.ktc4.backend.global.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.member.enums.MemberRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtProvider")
class JwtProviderTest {

    // 테스트 전용 값. 32바이트 이상이어야 한다.
    private static final String SECRET = "test-only-jwt-secret-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");
    private static final Duration VALIDITY = Duration.ofDays(7);

    private static JwtProvider providerAt(Instant now) {
        return new JwtProvider(SECRET, VALIDITY, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발급한 토큰에서 회원 ID 와 역할을 그대로 꺼낸다")
    void roundTrip() {
        JwtProvider provider = providerAt(NOW);

        IssuedToken token = provider.issue(7L, MemberRole.OWNER);

        assertThat(provider.parse(token.value())).contains(new AuthMember(7L, MemberRole.OWNER));
    }

    @Test
    @DisplayName("만료 시각은 발급 시각에 유효 기간을 더한 값이다")
    void expiresAfterValidity() {
        IssuedToken token = providerAt(NOW).issue(1L, MemberRole.ADMIN);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(VALIDITY));
    }

    @Test
    @DisplayName("만료 1초 전에는 통과하고, 만료 1초 뒤에는 거절한다")
    void rejectsAfterExpiry() {
        String token = providerAt(NOW).issue(1L, MemberRole.ADMIN).value();
        Instant expiresAt = NOW.plus(VALIDITY);

        assertThat(providerAt(expiresAt.minusSeconds(1)).parse(token)).isPresent();
        assertThat(providerAt(expiresAt.plusSeconds(1)).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 거절한다")
    void rejectsTokenSignedWithOtherKey() {
        JwtProvider other = new JwtProvider("another-test-only-secret-at-least-32-bytes",
                VALIDITY, Clock.fixed(NOW, ZoneOffset.UTC));
        String foreign = other.issue(1L, MemberRole.ADMIN).value();

        assertThat(providerAt(NOW).parse(foreign)).isEmpty();
    }

    @Test
    @DisplayName("역할을 ADMIN 으로 바꿔치기한 토큰은 서명이 맞지 않아 거절한다")
    void rejectsTamperedRole() {
        String[] parts = providerAt(NOW).issue(1L, MemberRole.OWNER).value().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("OWNER", "ADMIN").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(providerAt(NOW).parse(forged)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-token", "a.b.c"})
    @DisplayName("토큰 형식이 아니면 예외 없이 빈 값을 돌려준다")
    void rejectsMalformed(String token) {
        assertThat(providerAt(NOW).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("토큰에는 회원 ID·역할·시각만 담고 이메일 같은 개인정보는 담지 않는다")
    void containsNoPersonalData() throws Exception {
        String payload = providerAt(NOW).issue(1L, MemberRole.OWNER).value().split("\\.")[1];
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));

        assertThat(claims.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(
                List.of("sub", "role", "iat", "exp"));
    }

    // ── 가입 상태 확인용 토큰 ─────────────────────────────────────────────

    // 운영 코드가 발급하지 않는 모양의 토큰을 같은 키로 직접 만든다 — 그런 토큰이 들어와도 안전한지 보려고.
    private static String signedWith(Map<String, Object> claims) {
        return Jwts.builder()
                .subject("7")
                .claims(claims)
                .expiration(Date.from(NOW.plus(VALIDITY)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    @DisplayName("가입 상태 확인용 토큰에서는 역할 없는 사용자가 나온다 — 점주 토큰과 구별된다")
    void signupStatusRoundTrip() {
        JwtProvider provider = providerAt(NOW);

        IssuedToken token = provider.issueSignupStatus(7L);

        assertThat(provider.parse(token.value())).contains(AuthMember.signupStatus(7L));
        assertThat(provider.parse(token.value())).get().isNotEqualTo(new AuthMember(7L, MemberRole.OWNER));
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(VALIDITY));
    }

    @Test
    @DisplayName("가입 상태 확인용 토큰에는 역할을 담지 않는다 — 용도 칸을 모르는 코드가 점주 토큰으로 알아보지 못하게")
    void signupStatusTokenHasNoRole() throws Exception {
        String payload = providerAt(NOW).issueSignupStatus(7L).value().split("\\.")[1];
        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));

        assertThat(claims.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(
                List.of("sub", "scope", "iat", "exp"));
    }

    @Test
    @DisplayName("가입 상태 확인용 토큰도 만료 뒤에는 거절한다")
    void signupStatusExpires() {
        String token = providerAt(NOW).issueSignupStatus(7L).value();

        assertThat(providerAt(NOW.plus(VALIDITY).plusSeconds(1)).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("모르는 용도가 적힌 토큰은 거절한다 — 일반 토큰으로 넘기지 않는다")
    void rejectsUnknownScope() {
        String token = signedWith(Map.of("scope", "SOMETHING_NEW", "role", "ADMIN"));

        assertThat(providerAt(NOW).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("용도와 역할이 함께 적혀 있으면 용도만 따른다 — 관리자 역할이 적혀 있어도 제한된 사용자다")
    void scopeWinsOverRole() {
        String token = signedWith(Map.of("scope", "SIGNUP_STATUS", "role", "ADMIN"));

        assertThat(providerAt(NOW).parse(token)).contains(AuthMember.signupStatus(7L));
    }

    @Test
    @DisplayName("용도 칸을 지워 일반 토큰처럼 바꿔치기하면 서명이 맞지 않아 거절한다")
    void rejectsTamperedScope() {
        String[] parts = providerAt(NOW).issueSignupStatus(7L).value().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"scope\":\"SIGNUP_STATUS\"", "\"role\":\"OWNER\"").getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(forgedPayload).isNotEqualTo(parts[1]);   // 바꿔치기가 실제로 일어났는지
        assertThat(providerAt(NOW).parse(forged)).isEmpty();
    }

    @Test
    @DisplayName("용도도 역할도 없는 토큰은 거절한다")
    void rejectsTokenWithoutScopeAndRole() {
        assertThat(providerAt(NOW).parse(signedWith(Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("키가 32바이트보다 짧으면 기동을 막는다")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtProvider("short-secret", VALIDITY, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("키가 비어 있으면 임의 키로 동작하고, 그 키는 다른 인스턴스와 겹치지 않는다")
    void generatesRandomKeyWhenBlank() {
        JwtProvider first = new JwtProvider("", VALIDITY, Clock.fixed(NOW, ZoneOffset.UTC));
        JwtProvider second = new JwtProvider(null, VALIDITY, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = first.issue(1L, MemberRole.ADMIN).value();

        assertThat(first.parse(token)).isPresent();
        assertThat(second.parse(token)).isEmpty();
    }
}
