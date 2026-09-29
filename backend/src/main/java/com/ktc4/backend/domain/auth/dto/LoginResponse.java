package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.global.security.IssuedToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 로그인 성공 응답. 이후 요청에 {@code Authorization: Bearer <accessToken>} 으로 싣는다.
 *
 * @param accessToken 접근 토큰
 * @param tokenType   항상 {@code Bearer}
 * @param expiresAt   만료 시각(UTC). 지나면 다시 로그인한다
 * @param role        역할 — 앱이 관리자/점주 화면을 고르는 데 쓴다
 */
public record LoginResponse(
        @Schema(description = "접근 토큰") String accessToken,
        @Schema(description = "토큰 종류", example = "Bearer") String tokenType,
        @Schema(description = "만료 시각(UTC)", example = "2026-10-05T03:00:00Z") Instant expiresAt,
        @Schema(description = "역할", example = "OWNER") MemberRole role
) {
    private static final String BEARER = "Bearer";

    public static LoginResponse of(IssuedToken token, MemberRole role) {
        return new LoginResponse(token.value(), BEARER, token.expiresAt(), role);
    }
}
