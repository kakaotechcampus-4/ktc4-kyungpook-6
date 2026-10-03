package com.ktc4.backend.domain.checkin.dto;

import com.ktc4.backend.domain.checkin.entity.CheckIn;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 체크인 응답 DTO.
 *
 * <p>아동을 알아볼 수 있는 값({@code childId} 등)은 의도적으로 넣지 않는다. 점주에게 필요한 건
 * "확인됐다"는 결과뿐이고, 가게마다 아동 기록이 쌓이면 급식카드 낙인을 줄이려는 목적과 반대가 된다.
 */
public record CheckInResponse(
        @Schema(description = "체크인 기록 ID", example = "1")
        Long checkInId,

        @Schema(description = "체크인 시각", example = "2026-09-29T12:00:00")
        LocalDateTime checkedInAt
) {

    public static CheckInResponse from(CheckIn checkIn) {
        return new CheckInResponse(checkIn.getCheckInId(), checkIn.getCreatedAt());
    }
}
