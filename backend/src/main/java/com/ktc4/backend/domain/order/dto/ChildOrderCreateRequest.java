package com.ktc4.backend.domain.order.dto;

import com.ktc4.backend.domain.order.enums.ReceiveType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 아동 주문 요청. 메뉴 하나와 받는 방법을 고른다(수량·여러 메뉴는 받지 않는다 — 앱 화면이 하나만 고른다).
 */
public record ChildOrderCreateRequest(
        @Schema(description = "가게 ID", example = "10")
        @NotNull(message = "가게를 골라 주세요")
        @Positive(message = "가게 ID 가 올바르지 않습니다")
        Long storeId,

        @Schema(description = "메뉴 ID — 그 가게의 메뉴여야 한다", example = "3")
        @NotNull(message = "메뉴를 골라 주세요")
        @Positive(message = "메뉴 ID 가 올바르지 않습니다")
        Long menuId,

        @Schema(description = "받는 방법")
        @NotNull(message = "받는 방법을 골라 주세요")
        ReceiveType receiveType
) {
}
