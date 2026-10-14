package com.ktc4.backend.domain.store.validation;

import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NonOverlappingBusinessHoursValidator")
class NonOverlappingBusinessHoursValidatorTest {

    private final NonOverlappingBusinessHoursValidator validator = new NonOverlappingBusinessHoursValidator();

    // "MON 11:00-14:00; MON 14:00-21:00" 처럼 적은 줄들을 요청으로 바꾼다
    private static List<BusinessHourRequest> hours(String lines) {
        return Arrays.stream(lines.split(";"))
                .map(String::strip)
                .map(line -> {
                    String[] dayAndTimes = line.split(" ");
                    String[] times = dayAndTimes[1].split("-");
                    return new BusinessHourRequest(BusinessDay.valueOf(dayAndTimes[0]), times[0], times[1]);
                })
                .toList();
    }

    private boolean isValid(List<BusinessHourRequest> hours) {
        return validator.isValid(hours, null);
    }

    @ParameterizedTest(name = "[{index}] {0} → 통과 {1}")
    @CsvSource(delimiter = '|', textBlock = """
            MON 11:00-14:00; MON 13:00-21:00                     | false
            MON 11:00-14:00; MON 11:00-14:00                     | false
            MON 11:00-14:00; MON 14:00-21:00                     | true
            MON 11:00-21:00; TUE 11:00-21:00                     | true
            MON 06:00-09:00; MON 18:00-21:00; MON 08:00-10:00    | false
            MON 18:00-21:00; MON 06:00-09:00; MON 10:00-13:00    | true
            MON 10:00-20:00; MON 12:00-13:00                     | false
            MON 21:00-02:00; TUE 01:00-03:00                     | false
            MON 21:00-02:00; TUE 02:00-10:00                     | true
            SUN 22:00-03:00; MON 02:00-10:00                     | false
            SUN 22:00-03:00; MON 03:00-10:00                     | true
            SUN 22:00-03:00; MON 00:00-24:00                     | false
            MON 22:00-02:00; MON 23:00-01:00                     | false
            MON 00:00-24:00; MON 12:00-13:00                     | false
            SUN 22:00-02:00; MON 00:00-24:00                     | false
            MON 00:00-24:00; TUE 00:00-24:00                     | true
            MON 20:00-24:00; TUE 00:00-02:00                     | true
            MON 22:00-01:00; MON 00:30-02:00                     | true
            """)
    @DisplayName("같은 요일끼리, 그리고 자정을 넘긴 줄과 다음 요일(일→월 포함)이 겹치면 거절한다. 맞닿는 것은 통과")
    void detectsOverlaps(String lines, boolean expected) {
        assertThat(isValid(hours(lines))).isEqualTo(expected);
    }

    @Test
    @DisplayName("입력 순서와 상관없이 잡는다 — 이웃한 줄끼리만 비교하면 놓치는 겹침")
    void detectsOverlapRegardlessOfOrder() {
        List<BusinessHourRequest> lines = new ArrayList<>(hours(
                "MON 06:00-09:00; TUE 06:00-09:00; WED 06:00-09:00; MON 18:00-21:00; THU 06:00-09:00; MON 08:30-10:00"));
        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(lines, new Random(seed));
            assertThat(isValid(lines)).as("seed=%d", seed).isFalse();
        }
    }

    @Test
    @DisplayName("겹치지 않는 21줄(하루 세 칸 × 7일)은 통과한다")
    void acceptsFullWeekWithoutOverlap() {
        List<BusinessHourRequest> lines = new ArrayList<>();
        for (BusinessDay day : BusinessDay.values()) {
            lines.addAll(hours(day + " 06:00-09:00; " + day + " 10:00-13:00; " + day + " 14:00-17:00"));
        }
        assertThat(lines).hasSize(21);
        assertThat(isValid(lines)).isTrue();
    }

    @Test
    @DisplayName("형식이 틀린 줄은 예외 없이 건너뛴다 — 그 줄의 오류는 칸별 검증이 내고, 여기서 터지면 500 이 된다")
    void skipsMalformedLinesWithoutThrowing() {
        List<BusinessHourRequest> lines = new ArrayList<>();
        lines.add(null);
        lines.add(new BusinessHourRequest(null, "11:00", "21:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, null, "21:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, "25:00", "21:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, "24:00", "02:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, "11:00", "00:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, "12:00", "12:00"));
        lines.add(new BusinessHourRequest(BusinessDay.MON, "11:00", "21:00"));

        assertThat(isValid(lines)).isTrue();
    }

    @Test
    @DisplayName("형식이 틀린 줄이 섞여도 올바른 줄끼리의 겹침은 잡는다")
    void stillDetectsOverlapAmongValidLines() {
        List<BusinessHourRequest> lines = new ArrayList<>();
        lines.add(null);
        lines.add(new BusinessHourRequest(BusinessDay.MON, "25:00", "21:00"));
        lines.addAll(hours("MON 11:00-14:00; MON 13:00-21:00"));

        assertThat(isValid(lines)).isFalse();
    }

    @Test
    @DisplayName("목록이 null 이면 통과 — 없음은 @NotNull 이 따로 막는다")
    void nullListIsValid() {
        assertThat(isValid(null)).isTrue();
    }
}
