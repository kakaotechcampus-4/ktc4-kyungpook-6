package com.ktc4.backend.domain.checkin.dto;

import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.order.dto.OrderResponse;
import com.ktc4.backend.global.util.SeoulTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 체크인 응답 DTO.
 *
 * <p>아동을 알아볼 수 있는 값({@code childId} 등)은 의도적으로 넣지 않는다. 점주에게 필요한 건
 * "확인됐다"는 결과뿐이고, 가게마다 아동 기록이 쌓이면 급식카드 낙인을 줄이려는 목적과 반대가 된다.
 *
 * <p>그 아동이 이 가게에 낸 접수 대기 주문이 있으면 함께 싣는다 — 점주 앱의 결과 화면이 주문번호·메뉴·받는 방법을 보여 주고
 * "조리 시작하기"로 넘어가게. 주문에도 아동 정보는 없다.
 *
 * <p>체크인 시각은 한국 시각이다({@link SeoulTime}) — 서버 시간대가 UTC 여도 앱의 "확인 시각"이 맞게, 함께 싣는 주문 시각과 같은 기준으로.
 */
public record CheckInResponse(
        @Schema(description = "체크인 기록 ID", example = "1")
        Long checkInId,

        @Schema(description = "체크인 시각(한국 시각)", example = "2026-09-29T12:00:00")
        LocalDateTime checkedInAt,

        @Schema(description = "이 아동이 이 가게에 낸 접수 대기 주문. 없으면(주문 없이 왔거나 이미 조리 중) null", nullable = true)
        OrderResponse order
) {

    public static CheckInResponse from(CheckIn checkIn, OrderResponse order) {
        return new CheckInResponse(checkIn.getCheckInId(), SeoulTime.toSeoul(checkIn.getCreatedAt()), order);
    }
}
