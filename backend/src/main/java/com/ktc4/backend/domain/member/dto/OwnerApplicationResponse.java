package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 관리자가 보는 점주 가입 신청 한 건. 승인 여부를 판단할 사업자 정보를 함께 담는다.
 */
public record OwnerApplicationResponse(
        @Schema(description = "회원 ID — 승인 API 에 쓴다", example = "3") Long memberId,
        @Schema(description = "로그인 이메일", example = "owner@example.com") String email,
        @Schema(description = "사업자등록번호 (숫자 10자리)", example = "1234567890") String bizNo,
        @Schema(description = "상호명", example = "예시분식") String storeName,
        @Schema(description = "대표자 이름", example = "홍길동") String representativeName,
        @Schema(description = "계정 상태", example = "PENDING") MemberStatus status,
        @Schema(description = "신청 시각") LocalDateTime appliedAt,
        @Schema(description = "승인 시각. 승인 전에는 비어 있음") LocalDateTime reviewedAt
) {
    public static OwnerApplicationResponse from(Member member) {
        OwnerInfo info = member.getOwnerInfo();
        return new OwnerApplicationResponse(
                member.getMemberId(),
                member.getEmail(),
                info.getBizNo(),
                info.getStoreName(),
                info.getRepresentativeName(),
                member.getStatus(),
                member.getCreatedAt(),
                info.getReviewedAt()
        );
    }
}
