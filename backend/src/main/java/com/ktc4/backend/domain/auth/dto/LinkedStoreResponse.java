package com.ktc4.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인한 점주에게 연결된 가게 한 곳. 점주 앱은 이 {@code storeId} 로 체크인·가게 관리 API 를 부른다.
 */
public record LinkedStoreResponse(
        @Schema(description = "가게 ID", example = "12") Long storeId,
        @Schema(description = "가게 이름", example = "예시분식") String name
) {
}
