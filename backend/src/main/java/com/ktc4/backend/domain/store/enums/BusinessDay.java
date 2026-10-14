package com.ktc4.backend.domain.store.enums;

/**
 * 영업시간의 요일. 이름이 곧 API 로 주고받는 값이다(9/29 프론트와 합의한 {@code "MON"} ~ {@code "SUN"}).
 *
 * <p>{@link java.time.DayOfWeek} 를 쓰지 않는 이유: 그 이름은 {@code "MONDAY"} 라 합의한 값과 다르고,
 * 바꾸려면 변환 코드를 따로 둬야 한다. 날짜 계산이 필요해지면 그때 {@code DayOfWeek} 로 바꾸는 메서드를 더한다.
 *
 * <p>⚠️ 선언 순서가 곧 한 주의 순서다(월 → 일). 가게 상세의 영업시간 정렬과 자정 넘김의 "다음 요일" 계산이 이 순서를
 * 쓴다. 이름은 DB(요일 칸 검사 조건)와 가입 신청서 JSON 에도 들어가므로 바꾸지 않는다.
 */
public enum BusinessDay {
    MON, TUE, WED, THU, FRI, SAT, SUN
}
