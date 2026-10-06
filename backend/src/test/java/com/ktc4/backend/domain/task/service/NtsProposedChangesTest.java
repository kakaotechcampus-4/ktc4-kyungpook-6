package com.ktc4.backend.domain.task.service;

import com.ktc4.backend.domain.store.enums.StatusComparison;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NtsProposedChanges")
class NtsProposedChangesTest {

    @ParameterizedTest(name = "[{index}] {0} → status={1}")
    @CsvSource({
            "OPEN_BUT_SUSPENDED,   SUSPENDED",
            "OPEN_BUT_CLOSED,      CLOSED",
            "SUSPENDED_BUT_ACTIVE, OPEN",
            "SUSPENDED_BUT_CLOSED, CLOSED",
            "CLOSED_BUT_ACTIVE,    OPEN",
            "CLOSED_BUT_SUSPENDED, SUSPENDED"
    })
    @DisplayName("상태가 다르면 국세청 상태를 status 수정안으로 만든다")
    void proposesStatus(StatusComparison comparison, String expected) {
        assertThat(NtsProposedChanges.from(comparison)).isEqualTo(Map.of("status", expected));
    }

    @ParameterizedTest
    @EnumSource(value = StatusComparison.class, names = {"MATCH", "NTS_NOT_REGISTERED", "NOT_COMPARABLE"})
    @DisplayName("제안할 것이 없으면 빈 수정안이다")
    void proposesNothing(StatusComparison comparison) {
        assertThat(NtsProposedChanges.from(comparison)).isEmpty();
    }
}
