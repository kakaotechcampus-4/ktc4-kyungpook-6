package com.ktc4.backend.domain.store.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BusinessTimes")
class BusinessTimesTest {

    @ParameterizedTest(name = "\"{0}\" → {1}분")
    @CsvSource({"00:00, 0", "00:01, 1", "09:05, 545", "21:30, 1290", "23:59, 1439", "24:00, 1440"})
    @DisplayName("HH:mm 을 자정부터 지난 분으로 바꾼다 — 24:00 은 그날 끝 자정 1440")
    void toMinuteOfDay(String time, int expected) {
        assertThat(BusinessTimes.toMinuteOfDay(time)).hasValue(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"24:01", "25:00", "9:00", "12:60", "12:5", "1200", " 12:00", "12:00 ", "ab:cd"})
    @DisplayName("형식이 틀리면 예외 없이 빈 값 — 겹침 검사가 틀린 줄을 받아도 500 이 나지 않게")
    void invalidFormatIsEmpty(String time) {
        assertThat(BusinessTimes.toMinuteOfDay(time)).isEmpty();
    }

    @ParameterizedTest(name = "여는 \"{0}\"")
    @CsvSource({"00:00, 0", "23:59, 1439"})
    @DisplayName("여는 시간은 00:00 ~ 23:59")
    void openMinuteRange(String open, int expected) {
        assertThat(BusinessTimes.toOpenMinute(open)).hasValue(expected);
    }

    @ParameterizedTest(name = "닫는 \"{0}\"")
    @CsvSource({"00:01, 1", "24:00, 1440"})
    @DisplayName("닫는 시간은 00:01 ~ 24:00")
    void closeMinuteRange(String close, int expected) {
        assertThat(BusinessTimes.toCloseMinute(close)).hasValue(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"24:00", "9:00"})
    @DisplayName("24:00 에 열 수는 없다 — 여는 시간으로는 빈 값")
    void openCannotBeEndOfDay(String open) {
        assertThat(BusinessTimes.toOpenMinute(open)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"00:00", "9:00"})
    @DisplayName("자정에 닫으면 00:00 이 아니라 24:00 — 닫는 시간 00:00 은 빈 값")
    void closeCannotBeStartOfDay(String close) {
        assertThat(BusinessTimes.toCloseMinute(close)).isEmpty();
    }

    @ParameterizedTest(name = "{0}분 → \"{1}\"")
    @CsvSource({"0, 00:00", "5, 00:05", "545, 09:05", "600, 10:00", "1290, 21:30", "1439, 23:59", "1440, 24:00"})
    @DisplayName("분을 두 자리씩 채운 HH:mm 으로 바꾼다 — 1440 은 24:00")
    void toTime(int minute, String expected) {
        assertThat(BusinessTimes.toTime(minute)).isEqualTo(expected);
    }

    @Test
    @DisplayName("0 ~ 1440 모든 분이 HH:mm 을 거쳐 같은 분으로 돌아온다 — 여는 시간은 0 ~ 1439, 닫는 시간은 1 ~ 1440")
    void roundTripsEveryMinute() {
        for (int minute = 0; minute <= BusinessTimes.END_OF_DAY; minute++) {
            String time = BusinessTimes.toTime(minute);
            assertThat(BusinessTimes.toMinuteOfDay(time)).as(time).hasValue(minute);
            if (minute < BusinessTimes.END_OF_DAY) {
                assertThat(BusinessTimes.toOpenMinute(time)).as(time).hasValue(minute);
            }
            if (minute > 0) {
                assertThat(BusinessTimes.toCloseMinute(time)).as(time).hasValue(minute);
            }
        }
    }

    @ParameterizedTest(name = "{0}분")
    @ValueSource(ints = {-1, 1441})
    @DisplayName("범위 밖의 분은 예외 — DB 검사 조건이 막는 값이라 생기면 버그다")
    void toTimeRejectsOutOfRange(int minute) {
        assertThatThrownBy(() -> BusinessTimes.toTime(minute)).isInstanceOf(IllegalArgumentException.class);
    }
}
