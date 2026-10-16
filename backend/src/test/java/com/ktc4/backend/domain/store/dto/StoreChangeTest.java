package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 변경 한 번의 값 — 조사 결과 ID 는 조사 수정안 반영일 때만 있다는 규칙을 만들 때 막는지 본다.
 */
@DisplayName("StoreChange")
class StoreChangeTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 10, 0);

    @Test
    @DisplayName("조사 수정안 반영은 조사 결과 ID 와 넘겨받은 시각을 담는다")
    void taskApplyCarriesTaskAndTime() {
        assertThat(StoreChange.taskApply(4L, 50L, AT))
                .extracting(StoreChange::source, StoreChange::changedBy, StoreChange::taskId, StoreChange::changedAt)
                .containsExactly(StoreChangeSource.TASK_APPLY, 4L, 50L, AT);
    }

    @ParameterizedTest
    @EnumSource(value = StoreChangeSource.class, names = "TASK_APPLY", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("조사 수정안 반영이 아닌데 조사 결과 ID 가 있으면 만들지 않는다")
    void rejectsTaskIdOutsideTaskApply(StoreChangeSource source) {
        assertThatThrownBy(() -> new StoreChange(source, 1L, 50L, AT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조사 수정안 반영인데 조사 결과 ID 가 없으면 만들지 않는다")
    void rejectsTaskApplyWithoutTaskId() {
        assertThatThrownBy(() -> new StoreChange(StoreChangeSource.TASK_APPLY, 1L, null, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
