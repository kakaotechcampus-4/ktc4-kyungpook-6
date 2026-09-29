package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인한 본인 정보.
 */
public record MemberResponse(
        @Schema(description = "회원 ID", example = "1") Long memberId,
        @Schema(description = "이메일", example = "owner@example.com") String email,
        @Schema(description = "역할", example = "OWNER") MemberRole role,
        @Schema(description = "계정 상태", example = "APPROVED") MemberStatus status
) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getMemberId(), member.getEmail(), member.getRole(), member.getStatus());
    }
}
