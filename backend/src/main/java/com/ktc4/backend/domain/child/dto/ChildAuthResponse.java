package com.ktc4.backend.domain.child.dto;

import com.ktc4.backend.global.security.IssuedToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 아동 카드 인증 성공 응답. 이후 요청에 {@code Authorization: Bearer <accessToken>} 으로 싣는다.
 *
 * <p>칸 이름은 점주 로그인 응답({@code LoginResponse})과 같다 — 앱이 같은 저장 코드를 쓰게. 아동 번호는 싣지 않는다.
 *
 * @param accessToken 아동 토큰
 * @param tokenType   항상 {@code Bearer}
 * @param expiresAt   만료 시각(UTC). 지나면 카드로 다시 인증한다
 */
public record ChildAuthResponse(
        @Schema(description = "아동 토큰") String accessToken,
        @Schema(description = "토큰 종류", example = "Bearer") String tokenType,
        @Schema(description = "만료 시각(UTC)", example = "2026-11-10T03:00:00Z") Instant expiresAt
) {
    private static final String BEARER = "Bearer";

    public static ChildAuthResponse of(IssuedToken token) {
        return new ChildAuthResponse(token.value(), BEARER, token.expiresAt());
    }
}
