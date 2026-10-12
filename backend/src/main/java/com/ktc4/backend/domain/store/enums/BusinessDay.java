package com.ktc4.backend.domain.store.enums;

/**
 * 영업시간의 요일. 이름이 곧 API 로 주고받는 값이다(9/29 프론트와 합의한 {@code "MON"} ~ {@code "SUN"}).
 *
 * <p>{@link java.time.DayOfWeek} 를 쓰지 않는 이유: 그 이름은 {@code "MONDAY"} 라 합의한 값과 다르고,
 * 바꾸려면 변환 코드를 따로 둬야 한다. 날짜 계산이 필요해지면 그때 {@code DayOfWeek} 로 바꾸는 메서드를 더한다.
 */
public enum BusinessDay {
    MON, TUE, WED, THU, FRI, SAT, SUN
}
