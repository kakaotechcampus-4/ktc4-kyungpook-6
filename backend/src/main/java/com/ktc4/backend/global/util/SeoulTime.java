package com.ktc4.backend.global.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 서버 시간대로 저장한 시각을 한국 시각으로 바꾼다.
 *
 * <p>행의 시각({@code BaseTimeEntity} 의 JPA Auditing 등)은 서버 JVM 의 기본 시간대로 찍힌다. 배포 컨테이너는 시간대 설정이
 * 없어 UTC 이고, 로컬 개발 기기는 한국이다. 앱은 시간대 없는 시각의 "시:분"을 그대로 그려서, 서버가 UTC 면 "12:40 주문"이
 * "03:40" 으로 보인다. 그래서 앱에 시각을 줄 때 여기서 한국 시각으로 바꾼다 — 서버가 어느 시간대든 같은 값이 나간다.
 * 서버를 한국 시간대로 바꾸면 이 변환은 "같은 시간대 → 같은 값"이 되어 그대로 맞다.
 */
public final class SeoulTime {

    /** 한국 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private SeoulTime() {
    }

    /**
     * 서버 시간대로 찍은 시각을 한국 시각으로 바꾼다.
     *
     * @param systemTime 서버 시간대의 시각. null 이면 null
     * @return 같은 순간의 한국 시각(시간대 없이)
     */
    public static LocalDateTime toSeoul(LocalDateTime systemTime) {
        return toSeoul(systemTime, ZoneId.systemDefault());
    }

    static LocalDateTime toSeoul(LocalDateTime systemTime, ZoneId systemZone) {
        if (systemTime == null) {
            return null;
        }
        return systemTime.atZone(systemZone).withZoneSameInstant(ZONE).toLocalDateTime();
    }

    /**
     * 한국 날짜로 오늘의 시작(00:00)을 서버 시간대의 시각으로 적는다 — 서버 시간대로 저장된 시각과 비교하려고.
     *
     * @param now 지금
     * @return 서버 시간대로 적은 "한국 오늘 00:00"
     */
    public static LocalDateTime startOfSeoulToday(Instant now) {
        return startOfSeoulToday(now, ZoneId.systemDefault());
    }

    static LocalDateTime startOfSeoulToday(Instant now, ZoneId systemZone) {
        LocalDate seoulToday = now.atZone(ZONE).toLocalDate();
        return seoulToday.atStartOfDay(ZONE).withZoneSameInstant(systemZone).toLocalDateTime();
    }
}
