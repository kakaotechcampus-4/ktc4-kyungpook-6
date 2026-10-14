package com.ktc4.backend.domain.store.validation;

import com.ktc4.backend.domain.store.dto.MenuRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UniqueMenuIdsValidator")
class UniqueMenuIdsValidatorTest {

    private final UniqueMenuIdsValidator validator = new UniqueMenuIdsValidator();

    private static MenuRequest menu(Long menuId) {
        return new MenuRequest(menuId, "메뉴", true);
    }

    @Test
    @DisplayName("같은 menuId 가 떨어진 자리에 다시 나와도 거절한다")
    void rejectsDuplicateIdsAnywhere() {
        assertThat(validator.isValid(List.of(menu(3L), menu(null), menu(3L)), null)).isFalse();
    }

    @Test
    @DisplayName("새 메뉴(menuId 없음)는 여러 개여도 겹친 것이 아니다")
    void newMenusAreNotDuplicates() {
        assertThat(validator.isValid(List.of(menu(null), menu(null), menu(3L), menu(4L)), null)).isTrue();
    }

    @Test
    @DisplayName("빈 줄이 섞여도 예외 없이 건너뛴다 — 빈 줄의 오류는 칸별 검증이 낸다")
    void skipsNullRows() {
        List<MenuRequest> menus = new ArrayList<>();
        menus.add(null);
        menus.add(menu(3L));
        menus.add(null);

        assertThat(validator.isValid(menus, null)).isTrue();
    }

    @Test
    @DisplayName("목록이 null 이면 통과 — 없음은 @NotNull 이 따로 막는다")
    void nullListIsValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
