package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.enums.BusinessDay;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영업시간 한 줄의 형식 규칙. 가입과 가게 관리 저장이 같은 규칙을 쓰므로 경계값은 여기서 전부 확인하고,
 * 각 API 테스트는 이 검증이 실제로 이어져 도는지만 본다.
 */
@DisplayName("영업시간 한 줄 검증")
class BusinessHourRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> invalidFields(BusinessHourRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0}~{1}")
    @CsvSource({
            "00:00, 24:00",   // 24시간 영업
            "09:00, 18:00",
            "23:59, 24:00",   // 여는 시간의 끝
            "00:00, 00:01",   // 닫는 시간의 시작
            "09:00, 23:59",   // 자정 직전에 닫는다
            "21:00, 02:00",   // 자정을 넘겨 다음 날 닫는다
            "11:30, 14:00"
    })
    @DisplayName("올바른 시간은 통과한다 — 24시간 영업과 자정 넘김 포함")
    void acceptsValidTimes(String open, String close) {
        assertThat(invalidFields(new BusinessHourRequest(BusinessDay.MON, open, close))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"24:00", "9:00", "25:00", "12:60", "09:00:00", "0900", "", " 09:00", "09:00 ", "０９:００", "오전 9시"})
    @DisplayName("여는 시간은 00:00~23:59 의 HH:mm 만 받는다 — 24:00 에 여는 가게는 없다")
    void rejectsInvalidOpen(String open) {
        assertThat(invalidFields(new BusinessHourRequest(BusinessDay.MON, open, "21:00"))).containsExactly("open");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "24:01", "24:30", "9:00", "25:00", "21:00:00", ""})
    @DisplayName("닫는 시간은 00:01~24:00 의 HH:mm 만 받는다 — 자정에 닫으면 00:00 이 아니라 24:00")
    void rejectsInvalidClose(String close) {
        assertThat(invalidFields(new BusinessHourRequest(BusinessDay.MON, "09:00", close))).containsExactly("close");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12:00", "00:01", "23:59"})
    @DisplayName("여는 시간과 닫는 시간이 같으면 거절한다 — 0시간인지 24시간인지 알 수 없다")
    void rejectsSameOpenAndClose(String time) {
        assertThat(invalidFields(new BusinessHourRequest(BusinessDay.MON, time, time)))
                .containsExactly("openDifferentFromClose");
    }

    @Test
    @DisplayName("요일·여는 시간·닫는 시간은 모두 필수다")
    void requiresAllValues() {
        assertThat(invalidFields(new BusinessHourRequest(null, null, null)))
                .containsExactlyInAnyOrder("day", "open", "close");
    }
}
