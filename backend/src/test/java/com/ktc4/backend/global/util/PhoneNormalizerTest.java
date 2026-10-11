package com.ktc4.backend.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// 전화번호는 모두 가짜 값이다.
@DisplayName("PhoneNormalizer")
class PhoneNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @CsvSource({
            "010-0000-0000,     01000000000",
            "010 0000 0000,     01000000000",
            "010.0000.0000,     01000000000",
            "01000000000,       01000000000",
            "０１０-００００-００００, 01000000000",
            "+82 10-0000-0000,  01000000000",
            "+82 010-0000-0000, 01000000000",
            "82-10-0000-0000,   01000000000",
            "053-000-0000,      0530000000"
    })
    @DisplayName("표기가 달라도 같은 번호면 같은 값이 된다 — 하이픈·공백·점·전각 숫자·국가번호")
    void normalizes(String raw, String expected) {
        assertThat(PhoneNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null 이거나 숫자가 없으면 빈 문자열이다")
    void emptyWhenNoDigits() {
        assertThat(PhoneNormalizer.normalize(null)).isEmpty();
        assertThat(PhoneNormalizer.normalize("")).isEmpty();
        assertThat(PhoneNormalizer.normalize("전화없음")).isEmpty();
    }

    @Test
    @DisplayName("국내 번호가 82 로 시작해도 국가번호로 보지 않는다 — 11자리 이하는 그대로 둔다")
    void keepsDomesticNumberStartingWith82() {
        assertThat(PhoneNormalizer.normalize("82-000-0000")).isEqualTo("820000000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01000000000", "0100000000", "01100000000", "01600000000", "01900000000"})
    @DisplayName("010·011·016·017·018·019 로 시작하는 10~11자리는 휴대폰 번호다")
    void acceptsMobile(String normalized) {
        assertThat(PhoneNormalizer.isValidMobile(normalized)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0530000000", "0200000000", "01200000000", "010000000", "010000000000", "010-0000-0000"})
    @DisplayName("매장 전화, 없는 앞자리, 자릿수가 맞지 않는 값, 정리하지 않은 값은 휴대폰 번호가 아니다")
    void rejectsNonMobile(String normalized) {
        assertThat(PhoneNormalizer.isValidMobile(normalized)).isFalse();
    }

    @Test
    @DisplayName("null 은 휴대폰 번호가 아니다")
    void rejectsNull() {
        assertThat(PhoneNormalizer.isValidMobile(null)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @CsvSource({
            "010-0000-0001,    010-****-0001",
            "010 0000 0001,    010-****-0001",
            "01000000001,      010-****-0001",
            "+82 10-0000-0001, 010-****-0001",
            "0100000001,       010-****-0001",
            "053-000-0001,     053-****-0001"
    })
    @DisplayName("가운데 자리를 가리고 앞 3자리와 끝 4자리만 남긴다 — 표기가 달라도 같은 모양으로")
    void masksMiddle(String raw, String expected) {
        assertThat(PhoneNormalizer.mask(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("가린 값에는 가운데 자리가 한 글자도 남지 않는다")
    void maskedValueHidesMiddleDigits() {
        assertThat(PhoneNormalizer.mask("010-1111-0001")).isEqualTo("010-****-0001").doesNotContain("1111");
    }

    @Test
    @DisplayName("남길 자리가 모자라게 짧은 번호는 전체를 가린다 — 가릴 자리 없이 다 드러나지 않게")
    void masksShortNumberEntirely() {
        assertThat(PhoneNormalizer.mask("0000000")).isEqualTo("****");
        assertThat(PhoneNormalizer.mask("119")).isEqualTo("****");
    }

    @Test
    @DisplayName("번호가 없으면 가린 값도 없다")
    void maskOfNothingIsNull() {
        assertThat(PhoneNormalizer.mask(null)).isNull();
        assertThat(PhoneNormalizer.mask("")).isNull();
        assertThat(PhoneNormalizer.mask("전화없음")).isNull();
    }
}
