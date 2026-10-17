package com.ktc4.backend.domain.store.util;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 영업시간의 {@code "HH:mm"} 문자열을 자정부터 지난 분(0 ~ 1440)으로 바꾼다.
 *
 * <p>가게에는 시간을 분으로 저장한다 — {@code "24:00"}(그날 끝 자정)을 {@code LocalTime} 으로는 담을 수 없어서다.
 * 가입 신청을 가게로 옮길 때와 영업시간 겹침을 검사할 때 같은 규칙으로 바꾸도록 이 클래스 한 곳에 둔다.
 *
 * <p>형식이 틀리면 예외 대신 빈 값을 돌려준다. 겹침 검사는 형식이 틀린 줄도 받는데(그 줄의 오류는 칸별 검증이 낸다),
 * 거기서 예외가 나면 400 이 아니라 500 이 되기 때문이다.
 */
public final class BusinessTimes {

    /** 하루의 끝 자정({@code "24:00"}) */
    public static final int END_OF_DAY = 24 * 60;

    // 00:00 ~ 23:59 와 24:00. 여는 시간·닫는 시간 각각의 범위는 호출자가 본다
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):([0-5]\\d)|(24):(00)");

    private BusinessTimes() {
    }

    /**
     * {@code "HH:mm"} 을 자정부터 지난 분으로 바꾼다.
     *
     * @param time {@code "00:00"} ~ {@code "24:00"}
     * @return 0 ~ 1440. 형식이 틀리거나 {@code null} 이면 빈 값
     */
    public static OptionalInt toMinuteOfDay(String time) {
        if (time == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = HH_MM.matcher(time);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        if (matcher.group(3) != null) {
            return OptionalInt.of(END_OF_DAY);
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2)));
    }

    /**
     * 자정부터 지난 분을 {@code "HH:mm"} 으로 바꾼다. 1440 은 그날 끝 자정 {@code "24:00"} 이다.
     *
     * @param minuteOfDay 0 ~ 1440
     * @return {@code "00:00"} ~ {@code "24:00"}
     * @throws IllegalArgumentException 범위 밖이면 — DB 검사 조건이 막는 값이라 생기면 버그다
     */
    public static String toTime(int minuteOfDay) {
        if (minuteOfDay < 0 || minuteOfDay > END_OF_DAY) {
            throw new IllegalArgumentException("영업시간 분 값이 범위를 벗어났습니다 - " + minuteOfDay);
        }
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /**
     * 여는 시간을 분으로 바꾼다. 여는 시간은 {@code "00:00"} ~ {@code "23:59"} 다 — {@code "24:00"} 에 열 수는 없다.
     *
     * @param open 여는 시간
     * @return 0 ~ 1439. 형식이 틀리거나 범위 밖이면 빈 값
     */
    public static OptionalInt toOpenMinute(String open) {
        OptionalInt minute = toMinuteOfDay(open);
        return minute.isPresent() && minute.getAsInt() < END_OF_DAY ? minute : OptionalInt.empty();
    }

    /**
     * 닫는 시간을 분으로 바꾼다. 닫는 시간은 {@code "00:01"} ~ {@code "24:00"} 다 — 자정에 닫으면 {@code "00:00"} 이
     * 아니라 {@code "24:00"} 으로 적는다.
     *
     * @param close 닫는 시간
     * @return 1 ~ 1440. 형식이 틀리거나 범위 밖이면 빈 값
     */
    public static OptionalInt toCloseMinute(String close) {
        OptionalInt minute = toMinuteOfDay(close);
        return minute.isPresent() && minute.getAsInt() > 0 ? minute : OptionalInt.empty();
    }
}
