package com.ktc4.backend.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BizNoNormalizer")
class BizNoNormalizerTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "123-45-67890     | 1234567890",
                "1234567890       | 1234567890",
                "' 123 45 67890 ' | 1234567890",
                "12345-67890      | 1234567890",
                "１２３-４５-６７８９０ | 1234567890",
                "123.45.67890     | 1234567890"
        })
        @DisplayName("하이픈·공백·점·전각 숫자를 정리해 숫자만 남긴다")
        void keepsDigitsOnly(String raw, String expected) {
            assertThat(BizNoNormalizer.normalize(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("null 이면 빈 문자열을 반환한다")
        void nullBecomesEmpty() {
            assertThat(BizNoNormalizer.normalize(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "---", "사업자번호없음"})
        @DisplayName("숫자가 하나도 없으면 빈 문자열을 반환한다")
        void noDigitsBecomesEmpty(String raw) {
            assertThat(BizNoNormalizer.normalize(raw)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("숫자 10자리이면 유효하다")
        void tenDigitsIsValid() {
            assertThat(BizNoNormalizer.isValid("1234567890")).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"123456789", "12345678901", "123-45-67890", "12345678a0"})
        @DisplayName("null·빈 값·자릿수 불일치·숫자 외 문자가 있으면 유효하지 않다")
        void otherwiseInvalid(String value) {
            assertThat(BizNoNormalizer.isValid(value)).isFalse();
        }

        @Test
        @DisplayName("정규화하지 않은 전각 숫자 10자리는 유효하지 않다 (normalize 를 먼저 거쳐야 한다)")
        void fullWidthDigitsNeedNormalize() {
            assertThat(BizNoNormalizer.isValid("１２３４５６７８９０")).isFalse();
            assertThat(BizNoNormalizer.isValid(BizNoNormalizer.normalize("１２３４５６７８９０"))).isTrue();
        }

        @Test
        @DisplayName("0 으로 시작해도 10자리면 유효하다")
        void leadingZeroIsValid() {
            assertThat(BizNoNormalizer.isValid("0123456789")).isTrue();
        }
    }

    @Nested
    @DisplayName("실제 데이터에서 나올 법한 입력")
    class RealWorldInput {

        @Test
        @DisplayName("엑셀이 지수 표기(1.23457E+09)로 바꾼 값은 자릿수가 깨져 무효가 된다")
        void excelScientificNotationIsInvalid() {
            String normalized = BizNoNormalizer.normalize("1.23457E+09");

            assertThat(normalized).isEqualTo("12345709");
            assertThat(BizNoNormalizer.isValid(normalized)).isFalse();
        }

        @Test
        @DisplayName("엑셀이 앞자리 0 을 지운 9자리 값은 복구하지 않고 무효로 둔다")
        void excelDroppedLeadingZeroIsInvalid() {
            assertThat(BizNoNormalizer.isValid(BizNoNormalizer.normalize("123456789"))).isFalse();
        }

        @Test
        @DisplayName("한 칸에 전화번호가 섞여 있으면 숫자가 합쳐져 무효가 된다")
        void mixedWithPhoneNumberIsInvalid() {
            String normalized = BizNoNormalizer.normalize("123-45-67890 (대표 000-1234-5678)");

            assertThat(BizNoNormalizer.isValid(normalized)).isFalse();
        }

        @Test
        @DisplayName("눈에 안 보이는 공백(zero-width space)이 섞여도 숫자만 남긴다")
        void removesZeroWidthSpace() {
            char zeroWidthSpace = (char) 0x200B;
            String withZeroWidthSpace = "12345" + zeroWidthSpace + "67890";

            assertThat(withZeroWidthSpace).hasSize(11);
            assertThat(BizNoNormalizer.normalize(withZeroWidthSpace)).isEqualTo("1234567890");
        }
    }
}
