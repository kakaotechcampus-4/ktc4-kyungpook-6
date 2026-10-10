package com.ktc4.backend.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 점주 가입 승인 요청. 승인하면서 그 점주의 가게를 함께 확정한다.
 *
 * <p>가게 없이는 승인할 수 없다. 승인만 되고 가게가 없는 점주는 어느 가게에도 체크인할 수 없는 계정이 된다.
 */
public record OwnerApproveRequest(
        @Schema(description = "이 점주에게 연결할 가게 ID — 후보 가게 조회에서 고른다", example = "123")
        @NotNull(message = "연결할 가게를 선택해 주세요")
        Long storeId
) {
}
