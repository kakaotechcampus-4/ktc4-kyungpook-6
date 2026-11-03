package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.OpenMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 점주 앱 홈의 영업 상태 저장 — 방식과 켜고 끈 값을 함께 보낸다.
 *
 * <p>영업시간대로({@code SCHEDULE})로 바꿀 때도 {@code manualOpen} 을 보낸다 — 다시 직접 켜고 끄기로 바꾸면 그 값으로 돌아간다.
 */
public record OpenSettingRequest(
        @Schema(description = "영업 상태 방식 — SCHEDULE(영업시간대로) 또는 MANUAL(직접 켜고 끄기)", example = "MANUAL")
        @NotNull(message = "영업 상태 방식을 보내 주세요")
        OpenMode mode,

        @Schema(description = "직접 켜고 끄기에서 켜 두었는지. false 면 아동 앱에 '쉬는중' 으로 보인다", example = "false")
        @NotNull(message = "켜고 끈 값을 보내 주세요")
        Boolean manualOpen
) {
}
