package com.ktc4.backend.domain.verification.dto;

import com.ktc4.backend.domain.verification.entity.Verification;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 조사 결과 한 건에 남은 담당자의 확인 기록. */
public record VerificationResponse(
        @Schema(description = "CHANGE_STATUS(영업 상태만 반영) · UPDATE_INFO(그 밖의 항목도 반영) · "
                + "NO_ACTION(확인만 — 이 확인으로는 가게 정보를 바꾸지 않음)", example = "CHANGE_STATUS")
        VerificationAction action,

        @Schema(description = "확인한 시각. 확인 직후엔 가게의 lastCheckedAt 과 같고, 그 뒤 가게를 다시 확인하면 lastCheckedAt 이 더 새롭다")
        LocalDateTime verifiedAt
) {

    public static VerificationResponse from(Verification verification) {
        return new VerificationResponse(verification.getAction(), verification.getVerifiedAt());
    }
}
