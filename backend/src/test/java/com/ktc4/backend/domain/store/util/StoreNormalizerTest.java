package com.ktc4.backend.domain.store.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoreNormalizer")
class StoreNormalizerTest {

    @Nested
    @DisplayName("normalizeName")
    class NormalizeName {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "㈜예시분식          | 예시분식",
                "(주)예시분식         | 예시분식",
                "( 주 ) 예시분식      | 예시분식",
                "주식회사 예시분식     | 예시분식",
                "예시분식 주식회사     | 예시분식",
                "(유)예시분식         | 예시분식",
                "유한회사 예시분식     | 예시분식"
        })
        @DisplayName("법인 표기를 제거한다")
        void removesCorporateMarks(String raw, String expected) {
            assertThat(StoreNormalizer.normalizeName(raw)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "'  예시   분식  '     | 예시분식",
                "예시분식 (샘플점)     | 예시분식샘플점",
                "샘플카페[본점]       | 샘플카페본점",
                "SAMPLE CAFE        | samplecafe",
                "ＳＡＭＰＬＥ　카페      | sample카페",
                "B&B 카페           | bb카페",
                "카페 1+1           | 카페11"
        })
        @DisplayName("공백·괄호·특수문자를 지우고 영문은 소문자로 바꾼다")
        void keepsHangulAlphaDigitOnly(String raw, String expected) {
            assertThat(StoreNormalizer.normalizeName(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("괄호보다 법인 표기를 먼저 지워서 '(주)' 가 '주' 로 남지 않는다")
        void removesCorporateMarkBeforeParentheses() {
            assertThat(StoreNormalizer.normalizeName("(주)예시분식")).doesNotStartWith("주");
        }

        @Test
        @DisplayName("표기만 다른 같은 가게는 같은 값이 된다")
        void sameStoreDifferentNotation() {
            assertThat(StoreNormalizer.normalizeName("㈜예시분식(샘플점)"))
                    .isEqualTo(StoreNormalizer.normalizeName("예시분식 샘플점"));
        }

        @Test
        @DisplayName("null 이면 빈 문자열을 반환한다")
        void nullBecomesEmpty() {
            assertThat(StoreNormalizer.normalizeName(null)).isEmpty();
        }

        @Test
        @DisplayName("맥에서 만든 파일처럼 자모가 쪼개진(NFD) 한글도 같은 값이 된다")
        void recomposesDecomposedHangul() {
            String decomposed = Normalizer.normalize("예시분식", Normalizer.Form.NFD);

            assertThat(decomposed).isNotEqualTo("예시분식");
            assertThat(StoreNormalizer.normalizeName(decomposed)).isEqualTo("예시분식");
        }

        @Test
        @DisplayName("이모지는 지운다")
        void removesEmoji() {
            assertThat(StoreNormalizer.normalizeName("예시분식🍜")).isEqualTo("예시분식");
        }

        @Test
        @DisplayName("㈱ 기호도 법인 표기처럼 사라진다")
        void removesParenthesizedStockSign() {
            assertThat(StoreNormalizer.normalizeName("㈱예시분식")).isEqualTo("예시분식");
        }

        @Test
        @DisplayName("자음·모음만 쓴 글자는 지운다")
        void removesStandaloneJamo() {
            assertThat(StoreNormalizer.normalizeName("ㅋㅋ분식")).isEqualTo("분식");
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "주식 회사 예시분식    | 예시분식",
                "주 식 회 사 예시분식  | 예시분식",
                "예시분식 주식  회사   | 예시분식",
                "유한 회사 예시분식    | 예시분식"
        })
        @DisplayName("띄어 쓴 법인 표기도 제거한다")
        void removesSpacedCorporateMarks(String raw, String expected) {
            assertThat(StoreNormalizer.normalizeName(raw)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "(사)예시단체     | 예시단체",
                "㈔예시단체       | 예시단체",
                "사단법인 예시단체  | 예시단체",
                "사단 법인 예시단체 | 예시단체",
                "(재)예시단체     | 예시단체",
                "재단법인 예시단체  | 예시단체"
        })
        @DisplayName("사단법인·재단법인 표기도 제거한다")
        void removesNonProfitCorporateMarks(String raw, String expected) {
            assertThat(StoreNormalizer.normalizeName(raw)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"주식 회사 예시분식", "(사)예시단체", "㈜예시분식(샘플점)", "SAMPLE 카페 1+1", "예시분식🍜"})
        @DisplayName("정규화한 값을 다시 정규화해도 결과가 같다")
        void isIdempotent(String raw) {
            String once = StoreNormalizer.normalizeName(raw);

            assertThat(StoreNormalizer.normalizeName(once)).isEqualTo(once);
        }

        @ParameterizedTest
        @ValueSource(strings = {"東京食堂", "(주)", "🍜🍜"})
        @DisplayName("[알려진 한계] 한글·영문·숫자가 하나도 없으면 빈 문자열이 된다")
        void becomesEmptyWithoutHangulAlphaDigit(String raw) {
            assertThat(StoreNormalizer.normalizeName(raw)).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalizeAddress")
    class NormalizeAddress {

        @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
        @CsvSource(delimiter = '|', value = {
                "가상특별시 예시구 샘플로 123    | 가상특별시예시구샘플로123",
                "'  가상특별시  예시구 샘플로 123 ' | 가상특별시예시구샘플로123",
                "가상특별시 예시구 샘플로 ８０     | 가상특별시예시구샘플로80",
                "가상특별시 예시구 샘플로 80-1    | 가상특별시예시구샘플로80-1"
        })
        @DisplayName("전각 문자를 바꾸고 공백만 제거한다")
        void removesWhitespaceOnly(String raw, String expected) {
            assertThat(StoreNormalizer.normalizeAddress(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("줄바꿈·탭도 공백으로 보고 제거한다")
        void removesTabsAndNewlines() {
            assertThat(StoreNormalizer.normalizeAddress("가상특별시\t예시구\n샘플로 123"))
                    .isEqualTo("가상특별시예시구샘플로123");
        }

        @Test
        @DisplayName("하이픈을 남겨서 80-1 과 801 을 다른 주소로 구분한다")
        void keepsHyphen() {
            assertThat(StoreNormalizer.normalizeAddress("샘플로 80-1"))
                    .isNotEqualTo(StoreNormalizer.normalizeAddress("샘플로 801"));
        }

        @Test
        @DisplayName("null 이면 빈 문자열을 반환한다")
        void nullBecomesEmpty() {
            assertThat(StoreNormalizer.normalizeAddress(null)).isEmpty();
        }

        // 워드·한글 프로그램이나 웹 복사에서 일반 하이픈(-) 대신 들어오는 대시 문자들
        // 소스에 문자를 그대로 넣으면 리뷰에서 구분이 안 돼 코드값으로 적는다
        @ParameterizedTest(name = "[{index}] 문자 코드 {0}")
        @ValueSource(ints = {0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2212})
        @DisplayName("모양이 비슷한 대시 문자는 일반 하이픈으로 통일한다")
        void unifiesDashVariants(int dash) {
            assertThat(StoreNormalizer.normalizeAddress("샘플로 80" + (char) dash + "1"))
                    .isEqualTo("샘플로80-1");
        }

        // 웹에서 복사한 값에 섞이는, 화면에 보이지 않는 문자들 (zero-width space·joiner, BOM)
        @ParameterizedTest(name = "[{index}] 문자 코드 {0}")
        @ValueSource(ints = {0x200B, 0x200C, 0x200D, 0xFEFF})
        @DisplayName("눈에 보이지 않는 공백 문자도 제거한다")
        void removesInvisibleCharacters(int invisible) {
            assertThat(StoreNormalizer.normalizeAddress("샘플로" + (char) invisible + " 123"))
                    .isEqualTo("샘플로123");
        }
    }
}
