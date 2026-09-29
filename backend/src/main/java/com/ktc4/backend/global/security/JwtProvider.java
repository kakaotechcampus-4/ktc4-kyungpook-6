package com.ktc4.backend.global.security;

import com.ktc4.backend.domain.member.enums.MemberRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * 로그인한 회원에게 주는 접근 토큰(JWT)을 만들고 검증한다.
 *
 * <p>토큰에는 회원 ID({@code sub})와 역할({@code role})만 담는다. JWT 본문은 서명만 될 뿐 암호화되지
 * 않아 누구나 디코딩해 읽을 수 있으므로, 이메일 같은 개인정보는 넣지 않는다.
 *
 * <p>요청마다 DB 를 보지 않고 토큰만 믿는다. 그래서 계정 상태가 바뀌어도(승인 취소 등) 이미 발급한
 * 토큰은 만료 전까지 유효하다 — 갱신 토큰을 도입할 때 유효 기간을 줄여 함께 다룬다.
 */
@Slf4j
public class JwtProvider {

    // HS256 은 256비트(32바이트) 이상 키를 요구한다(RFC 7518 §3.2). 짧으면 jjwt 가 서명을 거부한다.
    private static final int MIN_SECRET_BYTES = 32;
    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration validity;
    private final Clock clock;

    /**
     * @param secret   서명 키. 비어 있으면 임의 키를 만든다 — 앱은 뜨지만 재시작하면 발급한 토큰이 모두 무효가 된다
     * @param validity 토큰 유효 기간
     * @param clock    현재 시각 기준. 테스트에서 만료를 재현하려고 주입받는다
     * @throws IllegalStateException 키가 32바이트보다 짧으면
     */
    public JwtProvider(String secret, Duration validity, Clock clock) {
        this.key = toKey(secret);
        this.validity = validity;
        this.clock = clock;
    }

    private static SecretKey toKey(String secret) {
        if (secret == null || secret.isBlank()) {
            // 키가 없다고 앱을 못 띄우면 설정 전에 머지된 개발 서버가 통째로 멈춘다. 임의 키는 추측할 수 없어
            // 보안상 문제는 없고, 불편(재시작 시 재로그인)만 남으니 경고로 알린다.
            log.warn("JWT_SECRET 이 없어 임의 키로 기동합니다 — 재시작하면 모든 로그인이 풀립니다. 배포 환경에는 반드시 설정하세요");
            return Jwts.SIG.HS256.key().build();
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다 (현재 " + bytes.length + "바이트)");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 접근 토큰을 발급한다.
     *
     * @param memberId 회원 ID
     * @param role     역할
     * @return 토큰과 만료 시각
     */
    public IssuedToken issue(Long memberId, MemberRole role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(validity);
        String token = Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * 토큰을 검증하고 담긴 사용자를 꺼낸다.
     *
     * @param token 토큰 문자열
     * @return 서명·만료가 모두 유효하면 사용자, 아니면 빈 값
     */
    public Optional<AuthMember> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (claims.getSubject() == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthMember(Long.valueOf(claims.getSubject()), MemberRole.valueOf(role)));
        } catch (JwtException | IllegalArgumentException e) {
            // 토큰 원문은 남기지 않는다 — 로그가 새면 그 토큰으로 그대로 로그인할 수 있다.
            log.debug("유효하지 않은 토큰 - {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
