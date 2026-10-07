package com.ktc4.backend.domain.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수정안 한 항목. 한글 표시는 프론트가 붙인다 (분류 {@code classification} 과 같은 방식). */
public record ProposedChangeResponse(
        @Schema(description = "바꿀 항목 — Store 필드명 (status · phone · addressRoad · name)", example = "status")
        String field,

        @Schema(description = "새 값. status 면 OPEN · SUSPENDED · CLOSED", example = "CLOSED")
        String value
) {
}
