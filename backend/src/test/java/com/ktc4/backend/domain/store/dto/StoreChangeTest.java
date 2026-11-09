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
 * 변경 한 번의 값 — 조사 결과 ID 는 <b>조사 결과에서 비롯된 경로</b>에만 있다는 규칙을 만들 때 막는지 본다.
 *
 * <p>그 경로는 둘이다. 조사 수정안 반영({@code TASK_APPLY})은 반드시 어느 결과인지 있어야 하고,
 * 확인 전화({@code CALL_APPLY})는 조사 결과를 보고 걸므로 있을 수 있지만 — 가게 목록에서 바로 거는
 * 길도 열려 있어 — 없을 수도 있다.
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
    @EnumSource(value = StoreChangeSource.class, names = {"TASK_APPLY", "CALL_APPLY"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("조사 결과에서 비롯되지 않은 경로에 조사 결과 ID 가 있으면 만들지 않는다")
    void rejectsTaskIdOutsideTaskPaths(StoreChangeSource source) {
        assertThatThrownBy(() -> new StoreChange(source, 1L, 50L, AT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("확인 전화는 조사 결과 ID 가 있어도 되고 없어도 된다 — 조사 결과 없이도 걸 수 있다")
    void callApplyTaskIdIsOptional() {
        assertThat(StoreChange.callApply(4L, 50L, AT))
                .extracting(StoreChange::source, StoreChange::taskId)
                .containsExactly(StoreChangeSource.CALL_APPLY, 50L);
        assertThat(StoreChange.callApply(4L, null, AT))
                .extracting(StoreChange::source, StoreChange::taskId)
                .containsExactly(StoreChangeSource.CALL_APPLY, null);
    }

    @Test
    @DisplayName("조사 수정안 반영인데 조사 결과 ID 가 없으면 만들지 않는다")
    void rejectsTaskApplyWithoutTaskId() {
        assertThatThrownBy(() -> new StoreChange(StoreChangeSource.TASK_APPLY, 1L, null, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
