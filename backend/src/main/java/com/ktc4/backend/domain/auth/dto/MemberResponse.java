package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 로그인한 본인 정보.
 */
public record MemberResponse(
        @Schema(description = "회원 ID", example = "1") Long memberId,
        @Schema(description = "이메일", example = "owner@example.com") String email,
        @Schema(description = "역할", example = "OWNER") MemberRole role,
        @Schema(description = "계정 상태", example = "APPROVED") MemberStatus status,
        @Schema(description = """
                연결된 가게. 처음 연결한 순서대로이고(끊었다 다시 이어도 순서는 그대로), 끊긴 연결은 빠진다.
                관리자와 승인 전 신청자는 빈 목록이다.
                한 점주가 여러 가게에 연결될 수 있어 목록으로 준다 — 가게를 고르는 화면이 없으면 첫 번째를 쓴다""")
        List<LinkedStoreResponse> stores
) {
    public static MemberResponse of(Member member, List<LinkedStoreResponse> stores) {
        return new MemberResponse(member.getMemberId(), member.getEmail(), member.getRole(), member.getStatus(),
                List.copyOf(stores));
    }
}
