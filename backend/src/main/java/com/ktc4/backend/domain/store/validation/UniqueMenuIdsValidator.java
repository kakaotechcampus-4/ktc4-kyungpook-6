package com.ktc4.backend.domain.store.validation;

import com.ktc4.backend.domain.store.dto.MenuRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link UniqueMenuIds} 를 검사한다.
 *
 * <p>빈 줄과 {@code menuId} 가 없는 줄은 건너뛴다 — 빈 줄의 오류는 칸별 검증이 내고, {@code menuId} 가 없으면 새 메뉴다.
 * 예외를 던지지 않는다(검증 중 예외는 400 이 아니라 500 이 된다).
 */
public class UniqueMenuIdsValidator implements ConstraintValidator<UniqueMenuIds, List<MenuRequest>> {

    @Override
    public boolean isValid(List<MenuRequest> menus, ConstraintValidatorContext context) {
        if (menus == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        for (MenuRequest menu : menus) {
            if (menu != null && menu.menuId() != null && !seen.add(menu.menuId())) {
                return false;
            }
        }
        return true;
    }
}
