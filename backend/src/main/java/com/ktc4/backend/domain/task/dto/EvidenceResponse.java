package com.ktc4.backend.domain.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 수정안의 근거 한 줄 (Signal 한 행). {@code field} 로 어느 수정안의 근거인지 짝짓는다. */
public record EvidenceResponse(
        @Schema(description = "어느 항목의 근거인가 — proposedChanges 의 field 와 같은 값", example = "status")
        String field,

        @Schema(description = "어디서 나온 근거인가. NTS: 국세청 대조(1차) · AI_WEB: AI 웹검색(2차). 라벨은 프론트가 붙인다",
                example = "NTS")
        String source,

        @Schema(description = "근거 설명", example = "국세청 사업자 상태: 폐업자")
        String description,

        @Schema(description = "출처 표기. 지금은 항상 null — source 를 보고 프론트가 붙인다", nullable = true)
        String sourceLabel,

        @Schema(description = "근거 링크. 국세청 근거는 없다", nullable = true, example = "https://example.com/notice")
        String sourceUrl
) {
}
