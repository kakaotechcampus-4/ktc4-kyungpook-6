package com.ktc4.backend.domain.signal.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChangeField 키 변환")
class ChangeFieldTest {

    @ParameterizedTest
    @EnumSource(ChangeField.class)
    @DisplayName("모든 상수는 자기 키로 다시 찾아진다")
    void roundTripsThroughKey(ChangeField field) {
        assertThat(ChangeField.fromKey(field.key())).contains(field);
    }

    @Test
    @DisplayName("키는 Store 필드명이다 — proposedChanges 와 AI 가 쓰는 이름")
    void keysAreStoreFieldNames() {
        assertThat(ChangeField.STATUS.key()).isEqualTo("status");
        assertThat(ChangeField.PHONE.key()).isEqualTo("phone");
        assertThat(ChangeField.ADDRESS_ROAD.key()).isEqualTo("addressRoad");
        assertThat(ChangeField.NAME.key()).isEqualTo("name");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"STATUS", "address", "hours"})
    @DisplayName("모르는 키·상수 이름·null 은 찾지 않는다")
    void unknownKeyIsEmpty(String key) {
        assertThat(ChangeField.fromKey(key)).isEmpty();
    }
}
