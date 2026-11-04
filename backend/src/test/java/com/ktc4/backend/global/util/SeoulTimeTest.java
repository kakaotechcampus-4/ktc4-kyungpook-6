package com.ktc4.backend.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 JVM 의 시간대가 무엇이든 앱에 한국 시각이 가는지 확인한다. 배포 컨테이너는 시간대 설정이 없어 UTC 로 보이고,
 * 로컬 개발 기기는 한국이라 로컬에서는 차이가 드러나지 않는다 — 그래서 시간대를 인자로 넣어 둘 다 본다.
 */
@DisplayName("SeoulTime")
class SeoulTimeTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("UTC 로 저장된 시각은 9시간 뒤의 한국 시각이 된다 — 03:40 이 12:40 으로")
    void fromUtc() {
        assertThat(SeoulTime.toSeoul(LocalDateTime.of(2026, 11, 4, 3, 40), UTC))
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 12, 40));
    }

    @Test
    @DisplayName("이미 한국 시간대로 저장된 시각은 그대로다 — 서버를 한국 시간대로 바꿔도 맞다")
    void fromSeoulUnchanged() {
        assertThat(SeoulTime.toSeoul(LocalDateTime.of(2026, 11, 4, 12, 40), SEOUL))
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 12, 40));
    }

    @Test
    @DisplayName("날짜를 넘긴다 — UTC 11/3 20:00 은 한국 11/4 05:00")
    void crossesDate() {
        assertThat(SeoulTime.toSeoul(LocalDateTime.of(2026, 11, 3, 20, 0), UTC))
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 5, 0));
    }

    @Test
    @DisplayName("없는 시각은 없는 시각이다")
    void nullStaysNull() {
        assertThat(SeoulTime.toSeoul(null, UTC)).isNull();
    }

    @Test
    @DisplayName("오늘의 시작은 한국 날짜 00:00 을 서버 시간대로 적은 값이다 — UTC 서버면 전날 15:00")
    void startOfTodayOnUtcServer() {
        Instant koreanMorning = Instant.parse("2026-11-04T01:00:00Z");   // 한국 11/4 10:00

        assertThat(SeoulTime.startOfSeoulToday(koreanMorning, UTC)).isEqualTo(LocalDateTime.of(2026, 11, 3, 15, 0));
        assertThat(SeoulTime.startOfSeoulToday(koreanMorning, SEOUL)).isEqualTo(LocalDateTime.of(2026, 11, 4, 0, 0));
    }

    @Test
    @DisplayName("UTC 로는 아직 어제여도 한국 날짜가 바뀌었으면 그 날의 시작이다 — 한국 00:30(UTC 전날 15:30)")
    void startOfTodayJustAfterKoreanMidnight() {
        Instant justAfterMidnight = Instant.parse("2026-11-03T15:30:00Z");   // 한국 11/4 00:30

        assertThat(SeoulTime.startOfSeoulToday(justAfterMidnight, UTC)).isEqualTo(LocalDateTime.of(2026, 11, 3, 15, 0));
    }
}
