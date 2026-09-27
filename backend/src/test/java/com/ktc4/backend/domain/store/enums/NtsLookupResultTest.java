package com.ktc4.backend.domain.store.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NtsLookupResult")
class NtsLookupResultTest {

    @Test
    @DisplayName("번호가 없거나 형식이 틀린 경우만 데이터 문제로 본다")
    void isDataProblem() {
        assertThat(NtsLookupResult.NO_BIZ_NO.isDataProblem()).isTrue();
        assertThat(NtsLookupResult.UNCONFIRMED.isDataProblem()).isFalse();
        assertThat(NtsLookupResult.CONFIRMED.isDataProblem()).isFalse();
    }
}
