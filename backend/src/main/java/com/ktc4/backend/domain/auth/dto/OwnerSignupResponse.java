package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.global.security.IssuedToken;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 점주 가입 신청 응답. 만들어진 계정과, 승인 여부를 확인할 때 쓰는 가입 상태 확인용 토큰을 담는다.
 *
 * <p>앱은 {@code statusToken} 을 {@code Authorization: Bearer <statusToken>} 으로 실어
 * {@code GET /api/auth/me} 를 부르고 {@code status} 를 본다. 이 토큰으로는 그 API 만 부를 수 있다 —
 * 승인된 뒤에는 로그인해서 접근 토큰을 받는다.
 *
 * @param memberId             회원 ID
 * @param email                이메일
 * @param role                 역할
 * @param status               계정 상태. 가입 직후에는 항상 PENDING
 * @param statusToken          가입 상태 확인용 토큰
 * @param tokenType            항상 {@code Bearer}
 * @param statusTokenExpiresAt 토큰 만료 시각(UTC). 지나면 로그인으로 상태를 확인한다
 */
public record OwnerSignupResponse(
        @Schema(description = "회원 ID", example = "3") Long memberId,
        @Schema(description = "이메일", example = "owner@example.com") String email,
        @Schema(description = "역할", example = "OWNER") MemberRole role,
        @Schema(description = "계정 상태", example = "PENDING") MemberStatus status,
        @Schema(description = "가입 상태 확인용 토큰 — GET /api/auth/me 만 부를 수 있습니다") String statusToken,
        @Schema(description = "토큰 종류", example = "Bearer") String tokenType,
        @Schema(description = "가입 상태 확인용 토큰의 만료 시각(UTC)", example = "2026-10-16T03:00:00Z")
        Instant statusTokenExpiresAt
) {
    private static final String BEARER = "Bearer";

    public static OwnerSignupResponse of(Member member, IssuedToken statusToken) {
        return new OwnerSignupResponse(member.getMemberId(), member.getEmail(), member.getRole(), member.getStatus(),
                statusToken.value(), BEARER, statusToken.expiresAt());
    }
}
