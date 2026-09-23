package com.ktc4.backend.domain.store.enums;

import com.ktc4.backend.domain.business.enums.BusinessState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatusComparison")
class StatusComparisonTest {

    @ParameterizedTest(name = "[{index}] 우리 {0} / 국세청 {1} → {2}")
    @CsvSource({
            "OPEN,      ACTIVE,         MATCH",
            "OPEN,      SUSPENDED,      OPEN_BUT_SUSPENDED",
            "OPEN,      CLOSED,         OPEN_BUT_CLOSED",
            "OPEN,      NOT_REGISTERED, NTS_NOT_REGISTERED",
            "SUSPENDED, ACTIVE,         SUSPENDED_BUT_ACTIVE",
            "SUSPENDED, SUSPENDED,      MATCH",
            "SUSPENDED, CLOSED,         SUSPENDED_BUT_CLOSED",
            "SUSPENDED, NOT_REGISTERED, NTS_NOT_REGISTERED",
            "CLOSED,    ACTIVE,         CLOSED_BUT_ACTIVE",
            "CLOSED,    SUSPENDED,      CLOSED_BUT_SUSPENDED",
            "CLOSED,    CLOSED,         MATCH",
            "CLOSED,    NOT_REGISTERED, NTS_NOT_REGISTERED"
    })
    @DisplayName("우리 상태와 국세청 상태를 비교한다")
    void compares(StoreStatus internal, BusinessState nts, StatusComparison expected) {
        assertThat(StatusComparison.of(internal, nts)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(BusinessState.class)
    @DisplayName("우리 상태가 UNKNOWN 이면 비교할 수 없다")
    void unknownInternalIsNotComparable(BusinessState nts) {
        assertThat(StatusComparison.of(StoreStatus.UNKNOWN, nts)).isEqualTo(StatusComparison.NOT_COMPARABLE);
    }

    @ParameterizedTest
    @EnumSource(StoreStatus.class)
    @DisplayName("국세청 상태를 확인하지 못했으면(null) 비교할 수 없다")
    void missingNtsIsNotComparable(StoreStatus internal) {
        assertThat(StatusComparison.of(internal, null)).isEqualTo(StatusComparison.NOT_COMPARABLE);
    }

    @Test
    @DisplayName("상태가 서로 다른 경우만 불일치로 본다")
    void isMismatch() {
        assertThat(StatusComparison.OPEN_BUT_CLOSED.isMismatch()).isTrue();
        assertThat(StatusComparison.CLOSED_BUT_ACTIVE.isMismatch()).isTrue();
        assertThat(StatusComparison.MATCH.isMismatch()).isFalse();
        assertThat(StatusComparison.NOT_COMPARABLE.isMismatch()).isFalse();
    }

    @Test
    @DisplayName("국세청 미등록은 상태 불일치가 아니라 데이터 문제로 본다")
    void notRegisteredIsDataProblemNotMismatch() {
        assertThat(StatusComparison.NTS_NOT_REGISTERED.isMismatch()).isFalse();
        assertThat(StatusComparison.NTS_NOT_REGISTERED.isDataProblem()).isTrue();
    }

    @Test
    @DisplayName("미등록 외에는 데이터 문제가 아니다")
    void othersAreNotDataProblem() {
        assertThat(StatusComparison.MATCH.isDataProblem()).isFalse();
        assertThat(StatusComparison.OPEN_BUT_CLOSED.isDataProblem()).isFalse();
        assertThat(StatusComparison.NOT_COMPARABLE.isDataProblem()).isFalse();
    }
}
