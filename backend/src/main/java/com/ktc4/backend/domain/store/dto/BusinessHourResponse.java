package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.BusinessDay;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 영업시간 한 줄. 같은 요일이 여러 줄일 수 있고(브레이크타임), 줄이 없는 요일은 휴무다.
 */
public record BusinessHourResponse(
        @Schema(description = "요일 (MON ~ SUN)", example = "MON") BusinessDay day,
        @Schema(description = "여는 시간 (HH:mm)", example = "11:00") String open,
        @Schema(description = "닫는 시간 (HH:mm). 24:00 은 자정, 여는 시간보다 이르면 다음 날", example = "21:00") String close
) {
}
