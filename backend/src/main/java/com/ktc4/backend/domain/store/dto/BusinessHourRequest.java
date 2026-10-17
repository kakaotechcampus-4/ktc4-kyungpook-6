package com.ktc4.backend.domain.store.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 영업시간 한 줄. 점주 가입 신청과 가게 관리 저장이 같은 모양을 쓴다.
 *
 * <p>같은 요일을 여러 줄 적을 수 있다(브레이크타임). 줄이 없는 요일은 휴무다.
 *
 * <p>시간을 {@code LocalTime} 이 아니라 {@code "HH:mm"} 문자열로 받는 이유: 자정에 닫는 가게를 {@code "24:00"} 으로
 * 적어야 하는데 {@code LocalTime} 은 그 값을 읽지 못한다(그날 끝 자정을 {@code 24:00} 으로 쓰는 방식은
 * Google Business Profile API 의 영업시간 표기와 같다). 문자열이면 형식 오류도 칸별 {@code errors} 로 응답에 실린다.
 * 여러 줄끼리 겹치는지는 줄 하나로는 알 수 없어 목록 칸에 붙은
 * {@link com.ktc4.backend.domain.store.validation.NonOverlappingBusinessHours} 가 확인한다.
 */
public record BusinessHourRequest(
        @Schema(description = "요일 (MON ~ SUN)", example = "MON")
        @NotNull(message = "요일을 골라 주세요")
        BusinessDay day,

        @Schema(description = "여는 시간 (HH:mm, 00:00 ~ 23:59)", example = "11:00")
        @NotNull(message = "여는 시간을 입력해 주세요")
        @Pattern(regexp = OPEN_TIME, message = "여는 시간은 00:00 ~ 23:59 사이의 HH:mm 이어야 합니다")
        String open,

        @Schema(description = """
                닫는 시간 (HH:mm, 00:01 ~ 24:00). 자정에 닫으면 24:00 이다.
                여는 시간보다 이르면 다음 날 닫는다는 뜻이다 (예: 21:00 ~ 02:00). 24시간 영업은 00:00 ~ 24:00""",
                example = "21:00")
        @NotNull(message = "닫는 시간을 입력해 주세요")
        @Pattern(regexp = CLOSE_TIME, message = "닫는 시간은 00:01 ~ 24:00 사이의 HH:mm 이어야 합니다")
        String close
) {
    /** 한 번에 받는 영업시간 줄 수의 상한. 하루 세 줄(오전·오후·저녁)씩 일주일이다. */
    public static final int MAX_LINES = 21;

    // 00:00 ~ 23:59
    static final String OPEN_TIME = "([01]\\d|2[0-3]):[0-5]\\d";
    // 00:01 ~ 23:59 와 24:00. 자정에 닫는 것은 00:00 이 아니라 24:00 으로 적는다 — 00:00 은 "하루의 시작"이다
    static final String CLOSE_TIME = "(?!00:00)(([01]\\d|2[0-3]):[0-5]\\d|24:00)";

    /**
     * 여는 시간과 닫는 시간이 달라야 한다. 같으면 0시간인지 24시간인지 알 수 없다.
     * 오류는 {@code openDifferentFromClose} 칸으로 나간다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "여는 시간과 닫는 시간이 같을 수 없습니다 — 24시간 영업은 00:00 ~ 24:00 으로 적어 주세요")
    public boolean isOpenDifferentFromClose() {
        return open == null || close == null || !open.equals(close);
    }
}
