package com.ktc4.backend.domain.business.dto;

import com.ktc4.backend.domain.business.enums.BusinessState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 국세청 응답 해석 규칙. 입력은 NtsClient 가 돌려주는 원본 DTO 이고, 번호·날짜는 모두 가짜 값이다.
@DisplayName("국세청 응답 해석")
class BusinessStatusTest {

    // 실제 응답에서 우리가 쓰는 세 필드만 채우고 나머지는 빈 문자열
    private static NtsBusinessStatus raw(String bNo, String bSttCd, String endDt) {
        return new NtsBusinessStatus(bNo, "", bSttCd, "", "", endDt, "", "", "", "", "");
    }

    @Nested
    @DisplayName("실제 응답 3종 해석")
    class RealResponses {

        @Test
        @DisplayName("계속사업자 → ACTIVE, 폐업일 없음")
        void active() {
            NtsBusinessStatus item = new NtsBusinessStatus(
                    "1234567890", "계속사업자", "01", "부가가치세 일반과세자", "01",
                    "", "N", "", "", "해당없음", "99");

            BusinessStatus status = BusinessStatus.from(item);

            assertThat(status.bizNo()).isEqualTo("1234567890");
            assertThat(status.state()).isEqualTo(BusinessState.ACTIVE);
            assertThat(status.closedAt()).isNull();
        }

        @Test
        @DisplayName("폐업자 → CLOSED, end_dt 를 폐업일로 읽는다 (tax_type 이 일반과세자여도)")
        void closed() {
            NtsBusinessStatus item = new NtsBusinessStatus(
                    "1234567890", "폐업자", "03", "부가가치세 일반과세자", "01",
                    "20260301", "N", "", "", "부가가치세 간이과세자", "02");

            BusinessStatus status = BusinessStatus.from(item);

            assertThat(status.state()).isEqualTo(BusinessState.CLOSED);
            assertThat(status.closedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("미등록 → NOT_REGISTERED, 폐업일 없음")
        void notRegistered() {
            NtsBusinessStatus item = new NtsBusinessStatus(
                    "0000000000", "", "", "국세청에 등록되지 않은 사업자등록번호입니다.", "",
                    "", "", "", "", "", "");

            BusinessStatus status = BusinessStatus.from(item);

            assertThat(status.bizNo()).isEqualTo("0000000000");
            assertThat(status.state()).isEqualTo(BusinessState.NOT_REGISTERED);
            assertThat(status.closedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("상태 코드 규칙")
    class StateCode {

        @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
        @CsvSource({
                "01, ACTIVE",
                "02, SUSPENDED",
                "03, CLOSED"
        })
        @DisplayName("01·02·03 을 각 상태로 바꾼다")
        void mapsKnownCodes(String code, BusinessState expected) {
            assertThat(BusinessState.fromCode(code)).isEqualTo(expected);
        }

        @Test
        @DisplayName("코드가 빈 문자열이면 NOT_REGISTERED")
        void emptyCodeIsNotRegistered() {
            assertThat(BusinessState.fromCode("")).isEqualTo(BusinessState.NOT_REGISTERED);
        }

        @Test
        @DisplayName("코드 필드가 아예 없으면(null) 미등록으로 넘기지 않고 예외를 던진다")
        void nullCodeThrows() {
            assertThatThrownBy(() -> BusinessState.fromCode(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"99", "1", "０１"})
        @DisplayName("모르는 코드는 미등록으로 넘기지 않고 예외를 던진다")
        void unknownCodeThrows(String code) {
            assertThatThrownBy(() -> BusinessState.fromCode(code))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("상태 글자(b_stt)와 코드(b_stt_cd)가 달라도 코드로 판단한다")
        void codeWinsOverText() {
            NtsBusinessStatus item = new NtsBusinessStatus(
                    "1234567890", "폐업자", "01", "", "", "", "", "", "", "", "");

            assertThat(BusinessStatus.from(item).state()).isEqualTo(BusinessState.ACTIVE);
        }
    }

    @Nested
    @DisplayName("폐업일 규칙")
    class ClosedAt {

        @Test
        @DisplayName("휴업자는 end_dt 에 값이 있어도 폐업일로 쓰지 않는다")
        void suspendedIgnoresEndDate() {
            BusinessStatus status = BusinessStatus.from(raw("1234567890", "02", "20260301"));

            assertThat(status.state()).isEqualTo(BusinessState.SUSPENDED);
            assertThat(status.closedAt()).isNull();
        }

        @Test
        @DisplayName("계속사업자인데 end_dt 에 날짜가 남아 있어도 폐업일로 쓰지 않는다 (재개업 등)")
        void activeIgnoresEndDate() {
            assertThat(BusinessStatus.from(raw("1234567890", "01", "20260301")).closedAt()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        @DisplayName("폐업자인데 end_dt 가 비어 있거나 공백뿐이면 폐업일은 null")
        void closedWithoutEndDate(String endDt) {
            assertThat(BusinessStatus.from(raw("1234567890", "03", endDt)).closedAt()).isNull();
        }

        @Test
        @DisplayName("end_dt 앞뒤 공백은 지우고 읽는다")
        void trimsEndDate() {
            assertThat(BusinessStatus.from(raw("1234567890", "03", " 20260301 ")).closedAt())
                    .isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("윤년 2월 29일은 읽고, 윤년이 아닌 해의 2월 29일은 예외를 던진다")
        void leapDay() {
            assertThat(BusinessStatus.from(raw("1234567890", "03", "20240229")).closedAt())
                    .isEqualTo(LocalDate.of(2024, 2, 29));
            assertThatThrownBy(() -> BusinessStatus.from(raw("1234567890", "03", "20250229")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("YYYYMMDD 형식이 아니면 예외를 던진다")
        void invalidDateThrows() {
            assertThatThrownBy(() -> BusinessStatus.from(raw("1234567890", "03", "2026-03-01")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("사업자번호 규칙")
    class BizNo {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("b_no 가 없으면 어느 번호의 결과인지 알 수 없어 예외를 던진다")
        void missingBizNoThrows(String bNo) {
            assertThatThrownBy(() -> BusinessStatus.from(raw(bNo, "01", "")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
