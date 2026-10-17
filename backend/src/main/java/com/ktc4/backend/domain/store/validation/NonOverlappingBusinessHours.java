package com.ktc4.backend.domain.store.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 영업시간 목록 안에서 시간대가 겹치지 않아야 한다. 영업시간 목록 칸에 붙인다.
 *
 * <p>줄 하나로는 알 수 없는 규칙이라 목록 전체를 본다. 같은 요일 줄끼리, 그리고 자정을 넘긴 줄(닫는 시간이 여는
 * 시간보다 이른 줄)이 다음 요일(일요일 다음은 월요일) 시간대와 겹치는지 확인한다. 끝과 시작이 맞닿는 것
 * (11:00 ~ 14:00, 14:00 ~ 21:00)은 겹침이 아니다.
 *
 * <p>칸 단위 제약이라 오류는 그 칸 이름(예: {@code businessHours})으로 응답의 {@code errors} 에 실린다.
 * 클래스 단위로 붙이면 칸 이름이 없는 오류가 되어 {@code errors} 에서 빠진다.
 */
@Documented
@Constraint(validatedBy = NonOverlappingBusinessHoursValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface NonOverlappingBusinessHours {

    String message() default "영업시간이 겹치는 줄이 있습니다 — 같은 요일, 또는 자정을 넘긴 시간과 다음 날 시간이 겹치지 않게 적어 주세요";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
