package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.MemberStatus;
import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자가 보는 점주 가입 신청 한 건. 승인 여부를 판단할 사업자 정보를 함께 담는다.
 */
public record OwnerApplicationResponse(
        @Schema(description = "회원 ID — 승인 API 에 쓴다", example = "3") Long memberId,
        @Schema(description = "로그인 이메일", example = "owner@example.com") String email,
        @Schema(description = "사업자등록번호 (숫자 10자리)", example = "1234567890") String bizNo,
        @Schema(description = "상호명", example = "예시분식") String storeName,
        @Schema(description = "대표자 이름", example = "홍길동") String representativeName,
        @Schema(description = "점주 휴대폰 번호 (숫자만). 이 칸이 생기기 전에 가입한 점주는 비어 있음", example = "01000000000")
        String phone,
        @Schema(description = "가게 주소 — 가입 때 적은 값. ⚠️ 저장은 다음 배포부터라, 그 전 신청은 비어 있음",
                example = "대구광역시 북구 대학로 80")
        String storeAddress,
        @Schema(description = "가게 전화번호 — 가입 때 적은 값. ⚠️ 저장은 다음 배포부터라, 그 전 신청은 비어 있음",
                example = "053-123-4567")
        String storePhone,
        @Schema(description = "영업시간 — 가입 때 적은 값. ⚠️ 저장은 다음 배포부터라, 그 전 신청은 빈 목록")
        List<BusinessHourResponse> businessHours,
        @Schema(description = "메뉴 — 가입 때 적은 값. ⚠️ 저장은 다음 배포부터라, 그 전 신청은 빈 목록")
        List<SignupMenuResponse> menus,
        @Schema(description = "계정 상태", example = "PENDING") MemberStatus status,
        @Schema(description = "신청 시각") LocalDateTime appliedAt,
        @Schema(description = "승인하거나 거절한 시각. 처리 전에는 비어 있음") LocalDateTime reviewedAt
) {
    public static OwnerApplicationResponse from(Member member) {
        OwnerInfo info = member.getOwnerInfo();
        return new OwnerApplicationResponse(
                member.getMemberId(),
                member.getEmail(),
                info.getBizNo(),
                info.getStoreName(),
                info.getRepresentativeName(),
                info.getPhone(),
                // 가게 정보는 아직 신청서에 저장하지 않는다 — 저장이 붙는 다음 PR 에서 채운다
                null,
                null,
                List.of(),
                List.of(),
                member.getStatus(),
                member.getCreatedAt(),
                info.getReviewedAt()
        );
    }
}
