package com.ktc4.backend.domain.store.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메뉴 목록 안에서 같은 {@code menuId} 가 두 번 나오면 안 된다. 메뉴 목록 칸에 붙인다.
 *
 * <p>새 메뉴({@code menuId} 없음)는 여러 개여도 겹친 것이 아니다. 줄 하나로는 알 수 없는 규칙이라 목록 전체를 본다.
 * 칸 단위 제약이라 오류는 그 칸 이름(예: {@code menus})으로 응답의 {@code errors} 에 실린다.
 */
@Documented
@Constraint(validatedBy = UniqueMenuIdsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueMenuIds {

    String message() default "같은 메뉴가 두 번 들어 있습니다 — 메뉴 ID 가 겹치지 않게 보내 주세요";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
