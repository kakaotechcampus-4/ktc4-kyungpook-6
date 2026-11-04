package com.ktc4.backend.domain.order.dto;

import com.ktc4.backend.domain.order.entity.ChildOrder;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.global.util.SeoulTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 주문 응답. 아동·점주·관리자가 같은 모양을 받는다.
 *
 * <p>아동을 알아볼 수 있는 값(아동 번호)은 싣지 않는다 — 체크인 응답과 같은 원칙이다. 주문번호(화면의 "#1024")는 {@code orderId} 다.
 * 시각은 모두 한국 시각이고 시간대를 붙이지 않는다(앱이 그대로 "시:분"을 그린다).
 *
 * <p>가게 이름을 읽으므로 트랜잭션 안에서 만든다.
 */
public record OrderResponse(
        @Schema(description = "주문 ID — 화면의 주문번호", example = "1024") Long orderId,
        @Schema(description = "가게 ID", example = "10") Long storeId,
        @Schema(description = "가게 이름", example = "맛나 치킨") String storeName,
        @Schema(description = "주문 당시 메뉴 이름", example = "후라이드 치킨") String menuName,
        @Schema(description = "받는 방법") ReceiveType receiveType,
        @Schema(description = "상태 — RECEIVED(접수 대기)·COOKING(조리 중)·DONE(전달 완료)·CANCELED(취소)") OrderStatus status,
        @Schema(description = "주문 시각(한국 시각)", example = "2026-11-04T12:40:00") LocalDateTime orderedAt,
        @Schema(description = "조리 시작 시각(한국 시각). 아직이면 null", example = "2026-11-04T12:55:00") LocalDateTime cookingStartedAt,
        @Schema(description = "전달 완료 시각(한국 시각). 아직이면 null") LocalDateTime completedAt,
        @Schema(description = "취소 시각(한국 시각). 취소하지 않았으면 null") LocalDateTime canceledAt
) {

    public static OrderResponse from(ChildOrder order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getStore().getStoreId(),
                order.getStore().getName(),
                order.getMenuName(),
                order.getReceiveType(),
                order.getStatus(),
                SeoulTime.toSeoul(order.getCreatedAt()),
                SeoulTime.toSeoul(order.getCookingStartedAt()),
                SeoulTime.toSeoul(order.getCompletedAt()),
                SeoulTime.toSeoul(order.getCanceledAt()));
    }
}
