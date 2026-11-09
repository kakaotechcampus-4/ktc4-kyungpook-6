package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.OpenMode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 저장한 뒤의 영업 상태 방식과 켜고 끈 값.
 */
public record OpenSettingResponse(
        @Schema(description = "영업 상태 방식 — SCHEDULE(영업시간대로) 또는 MANUAL(직접 켜고 끄기)", example = "MANUAL")
        OpenMode mode,
        @Schema(description = "직접 켜고 끄기에서 켜 두었는지", example = "false")
        boolean manualOpen
) {
}
