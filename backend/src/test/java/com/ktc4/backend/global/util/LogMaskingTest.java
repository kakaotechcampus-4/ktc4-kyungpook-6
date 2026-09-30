package com.ktc4.backend.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogMasking")
class LogMaskingTest {

    @ParameterizedTest
    @CsvSource({
            "owner@example.com, o***@example.com",
            "a@example.com,     a***@example.com",
            "no-at-sign,        ***",
            "@example.com,      ***"
    })
    @DisplayName("이메일은 아이디 첫 글자와 도메인만 남긴다")
    void masksEmail(String email, String expected) {
        assertThat(LogMasking.maskEmail(email)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null 은 null")
    void keepsNull() {
        assertThat(LogMasking.maskEmail(null)).isNull();
    }
}
